//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.ExtensionStack;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.Negotiated;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketConnection;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractHandshaker implements Handshaker
{
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractHandshaker.class);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);

    @Override
    public boolean upgradeRequest(WebSocketNegotiator negotiator, Request request, Response response, Callback callback, WebSocketComponents components, Configuration.Customizer defaultCustomizer) throws IOException
    {
        if (!isWebSocketUpgradeRequest(request))
            return false;

        WebSocketNegotiation negotiation = newNegotiation(request, response, callback, components);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiation {}", negotiation);
        negotiation.negotiate();

        if (!validateNegotiation(negotiation))
            return false;

        // From this point on, we can only return true or throw.

        // Negotiate the FrameHandler
        FrameHandler handler = negotiator.negotiate(negotiation.getRequest(), negotiation.getResponse(), negotiation.getCallback());
        if (handler == null)
            return true;

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
            response.getHeaders().put(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, ExtensionConfig.toHeaderValue(negotiation.getNegotiatedExtensions()));
        else
            response.getHeaders().put(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, (String)null);

        Negotiated negotiated = new Negotiated(request.getHttpURI().toURI(), protocol, request.isSecure(), extensionStack, WebSocketConstants.SPEC_VERSION_STRING);

        // Create the Session
        WebSocketCoreSession coreSession = newWebSocketCoreSession(request, handler, negotiated, components);
        if (defaultCustomizer != null)
            defaultCustomizer.customize(coreSession);
        negotiator.customize(coreSession);

        if (LOG.isDebugEnabled())
            LOG.debug("session {}", coreSession);

        WebSocketConnection connection = createWebSocketConnection(request, coreSession);
        if (LOG.isDebugEnabled())
            LOG.debug("connection {}", connection);
        if (connection == null)
            throw new WebSocketException("not upgraded: no connection");

        ConnectionMetaData connectionMetaData = request.getConnectionMetaData();
        HttpConfiguration httpConfig = connectionMetaData.getHttpConfiguration();
        connection.setUseInputDirectByteBuffers(httpConfig.isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(httpConfig.isUseOutputDirectByteBuffers());

        connectionMetaData.getConnector().getEventListeners().forEach(connection::addEventListener);

        prepareResponse(response, negotiation);

        request.setAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, connection);

        negotiation.getRequest().upgrade(request);

        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={} framehandler={}", connection, coreSession, handler);
        response.write(true, null, callback);
        return true;
    }

    protected abstract WebSocketNegotiation newNegotiation(Request request, Response response, Callback callback, WebSocketComponents webSocketComponents);

    @Override
    public boolean isWebSocketUpgradeRequest(Request request)
    {
        String wsVersionHeader = request.getHeaders().get(HttpHeader.SEC_WEBSOCKET_VERSION);
        if (!WebSocketConstants.SPEC_VERSION_STRING.equals(wsVersionHeader))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: unsupported version {} {}", wsVersionHeader, request);
            return false;
        }

        return true;
    }

    protected boolean validateNegotiation(WebSocketNegotiation negotiation)
    {
        if (!negotiation.validateHeaders())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no upgrade header or connection upgrade {}", negotiation.getRequest());
            return false;
        }

        return true;
    }

    protected WebSocketCoreSession newWebSocketCoreSession(Request request, FrameHandler handler, Negotiated negotiated, WebSocketComponents components)
    {
        Context context = request.getContext();
        return new WebSocketCoreSession(handler, Behavior.SERVER, negotiated, components)
        {
            @Override
            protected void handle(Runnable runnable)
            {
                context.run(runnable);
            }
        };
    }

    protected abstract WebSocketConnection createWebSocketConnection(Request baseRequest, WebSocketCoreSession coreSession);

    protected WebSocketConnection newWebSocketConnection(EndPoint endPoint, Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, WebSocketCoreSession coreSession)
    {
        WebSocketConnection connection = new WebSocketConnection(endPoint, executor, scheduler, byteBufferPool, coreSession);
        coreSession.setWebSocketConnection(connection);
        return connection;
    }

    protected abstract void prepareResponse(Response response, WebSocketNegotiation negotiation);
}
