//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractHandshaker implements Handshaker
{
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractHandshaker.class);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);

    @Override
    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response, WebSocketComponents components, Configuration.Customizer defaultCustomizer) throws IOException
    {
        if (!validateRequest(request))
            return false;

        // After negotiation these can be set to copy data from request and disable unavailable methods.
        UpgradeHttpServletRequest upgradeRequest = new UpgradeHttpServletRequest(request);
        UpgradeHttpServletResponse upgradeResponse = new UpgradeHttpServletResponse(response);

        WebSocketNegotiation negotiation = newNegotiation(upgradeRequest, upgradeResponse, components);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiation {}", negotiation);
        negotiation.negotiate();

        if (!validateNegotiation(negotiation))
            return false;

        // Negotiate the FrameHandler
        FrameHandler handler = negotiator.negotiate(negotiation);
        if (!validateFrameHandler(handler, response))
            return false;

        // Handle error responses
        Request baseRequest = negotiation.getBaseRequest();
        if (response.isCommitted())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: response committed {}", request);
            baseRequest.setHandled(true);
            return false;
        }
        int httpStatus = response.getStatus();
        if (httpStatus > 200)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: invalid http code {} {}", httpStatus, request);
            response.flushBuffer();
            baseRequest.setHandled(true);
            return false;
        }

        // Validate negotiated protocol.
        String protocol = negotiation.getSubprotocol();
        List<String> offeredProtocols = negotiation.getOfferedSubprotocols();
        if (protocol != null)
        {
            if (!offeredProtocols.contains(protocol))
                throw new WebSocketException("not upgraded: selected a protocol not present in offered protocols");
        }

        // validate negotiated extensions
        for (ExtensionConfig config : negotiation.getNegotiatedExtensions())
        {
            if (config.getName().startsWith("@"))
                continue;

            long matches = negotiation.getOfferedExtensions().stream().filter(c -> config.getName().equalsIgnoreCase(c.getName())).count();
            if (matches < 1)
                throw new WebSocketException("Upgrade failed: negotiated extension not requested");

            matches = negotiation.getNegotiatedExtensions().stream().filter(c -> config.getName().equalsIgnoreCase(c.getName())).count();
            if (matches > 1)
                throw new WebSocketException("Upgrade failed: multiple negotiated extensions of the same name");
        }

        // Create and Negotiate the ExtensionStack. (ExtensionStack can drop any extensions or their parameters.)
        ExtensionStack extensionStack = new ExtensionStack(components, Behavior.SERVER);
        extensionStack.negotiate(negotiation.getOfferedExtensions(), negotiation.getNegotiatedExtensions());
        negotiation.setNegotiatedExtensions(extensionStack.getNegotiatedExtensions());
        if (extensionStack.hasNegotiatedExtensions())
            baseRequest.getResponse().setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, ExtensionConfig.toHeaderValue(negotiation.getNegotiatedExtensions()));
        else
            baseRequest.getResponse().setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, null);

        Negotiated negotiated = new Negotiated(baseRequest.getHttpURI().toURI(), protocol, baseRequest.isSecure(), extensionStack, WebSocketConstants.SPEC_VERSION_STRING);

        // Create the Session
        WebSocketCoreSession coreSession = newWebSocketCoreSession(upgradeRequest, handler, negotiated, components);
        if (defaultCustomizer != null)
            defaultCustomizer.customize(coreSession);
        negotiator.customize(coreSession);

        if (LOG.isDebugEnabled())
            LOG.debug("session {}", coreSession);

        WebSocketConnection connection = createWebSocketConnection(baseRequest, coreSession);
        if (LOG.isDebugEnabled())
            LOG.debug("connection {}", connection);
        if (connection == null)
            throw new WebSocketException("not upgraded: no connection");

        HttpChannel httpChannel = baseRequest.getHttpChannel();
        HttpConfiguration httpConfig = httpChannel.getHttpConfiguration();
        connection.setUseInputDirectByteBuffers(httpConfig.isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(httpChannel.isUseOutputDirectByteBuffers());

        httpChannel.getConnector().getEventListeners().forEach(connection::addEventListener);

        coreSession.setWebSocketConnection(connection);

        baseRequest.setHandled(true);
        Response baseResponse = baseRequest.getResponse();
        prepareResponse(baseResponse, negotiation);
        if (httpConfig.getSendServerVersion())
            baseResponse.getHttpFields().put(SERVER_VERSION);
        baseResponse.flushBuffer();

        baseRequest.setAttribute(HttpTransport.UPGRADE_CONNECTION_ATTRIBUTE, connection);

        // Save state from request/response and remove reference to the base request/response.
        upgradeRequest.upgrade();
        upgradeResponse.upgrade();
        negotiation.upgrade();

        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={} framehandler={}", connection, coreSession, handler);

        return true;
    }

    protected abstract boolean validateRequest(HttpServletRequest request);

    protected abstract WebSocketNegotiation newNegotiation(HttpServletRequest request, HttpServletResponse response, WebSocketComponents webSocketComponents);

    protected abstract boolean validateFrameHandler(FrameHandler frameHandler, HttpServletResponse response);

    protected boolean validateNegotiation(WebSocketNegotiation negotiation)
    {
        if (!negotiation.validateHeaders())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no upgrade header or connection upgrade {}", negotiation.getBaseRequest());
            return false;
        }

        if (!WebSocketConstants.SPEC_VERSION_STRING.equals(negotiation.getVersion()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: unsupported version {} {}", negotiation.getVersion(), negotiation.getBaseRequest());
            return false;
        }

        return true;
    }

    protected WebSocketCoreSession newWebSocketCoreSession(HttpServletRequest request, FrameHandler handler, Negotiated negotiated, WebSocketComponents components)
    {
        final ContextHandler contextHandler = ContextHandler.getContextHandler(request.getServletContext());
        return new WebSocketCoreSession(handler, Behavior.SERVER, negotiated, components)
        {
            @Override
            protected void handle(Runnable runnable)
            {
                if (contextHandler != null)
                    contextHandler.handle(runnable);
                else
                    super.handle(runnable);
            }
        };
    }

    protected abstract WebSocketConnection createWebSocketConnection(Request baseRequest, WebSocketCoreSession coreSession);

    protected WebSocketConnection newWebSocketConnection(EndPoint endPoint, Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, RetainableByteBufferPool retainableByteBufferPool, WebSocketCoreSession coreSession)
    {
        return new WebSocketConnection(endPoint, executor, scheduler, byteBufferPool, retainableByteBufferPool, coreSession);
    }

    protected abstract void prepareResponse(Response response, WebSocketNegotiation negotiation);
}
