//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public abstract class AbstractHandshaker implements Handshaker
{
    protected static final Logger LOG = Log.getLogger(RFC8441Handshaker.class);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);

    @Override
    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response, FrameHandler.Customizer defaultCustomizer) throws IOException
    {
        if (!validateRequest(request))
            return false;

        Negotiation negotiation = newNegotiation(request, response, new WebSocketComponents());
        if (LOG.isDebugEnabled())
            LOG.debug("negotiation {}", negotiation);
        negotiation.negotiate();

        if (!validateNegotiation(negotiation))
            return false;

        // Negotiate the FrameHandler
        FrameHandler handler = negotiator.negotiate(negotiation);
        if (handler == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no frame handler provided {}", request);
            return false;
        }

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


        // Validate negotiated protocol
        String protocol = negotiation.getSubprotocol();
        List<String> offeredProtocols = negotiation.getOfferedSubprotocols();
        if (protocol != null)
        {
            if (!offeredProtocols.contains(protocol))
                throw new WebSocketException("not upgraded: selected a protocol not present in offered protocols");
        }
        else
        {
            if (!offeredProtocols.isEmpty())
                throw new WebSocketException("not upgraded: no protocol selected from offered protocols");
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

        // Create and Negotiate the ExtensionStack
        ExtensionStack extensionStack = negotiation.getExtensionStack();

        Negotiated negotiated = new Negotiated(baseRequest.getHttpURI().toURI(), protocol, baseRequest.isSecure(), extensionStack, WebSocketConstants.SPEC_VERSION_STRING);

        // Create the Session
        WebSocketCoreSession coreSession = newWebSocketCoreSession(handler, negotiated);
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

        Response baseResponse = baseRequest.getResponse();
        prepareResponse(baseResponse, negotiation);
        if (httpConfig.getSendServerVersion())
            baseResponse.getHttpFields().put(SERVER_VERSION);
        baseResponse.flushBuffer();
        baseRequest.setHandled(true);

        baseRequest.setAttribute(HttpTransport.UPGRADE_CONNECTION_ATTRIBUTE, connection);

        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={} framehandler={}", connection, coreSession, handler);

        return true;
    }

    protected abstract boolean validateRequest(HttpServletRequest request);

    protected abstract Negotiation newNegotiation(HttpServletRequest request, HttpServletResponse response, WebSocketComponents webSocketComponents);

    protected boolean validateNegotiation(Negotiation negotiation)
    {
        if (!negotiation.isSuccessful())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no upgrade header or connection upgrade", negotiation.getBaseRequest());
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

    protected WebSocketCoreSession newWebSocketCoreSession(FrameHandler handler, Negotiated negotiated)
    {
        return new WebSocketCoreSession(handler, Behavior.SERVER, negotiated);
    }

    protected abstract WebSocketConnection createWebSocketConnection(Request baseRequest, WebSocketCoreSession coreSession);

    protected WebSocketConnection newWebSocketConnection(EndPoint endPoint, Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, WebSocketCoreSession coreSession)
    {
        return new WebSocketConnection(endPoint, executor, scheduler, byteBufferPool, coreSession);
    }

    protected abstract void prepareResponse(Response response, Negotiation negotiation);
}
