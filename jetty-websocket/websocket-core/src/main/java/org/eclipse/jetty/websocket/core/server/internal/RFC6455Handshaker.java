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
import java.util.concurrent.Executor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
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
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public final class RFC6455Handshaker implements Handshaker
{
    static final Logger LOG = Log.getLogger(RFC6455Handshaker.class);
    private static final HttpField UPGRADE_WEBSOCKET = new PreEncodedHttpField(HttpHeader.UPGRADE, "WebSocket");
    private static final HttpField CONNECTION_UPGRADE = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeader.UPGRADE.asString());
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);

    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response,
                                  FrameHandler.Customizer defaultCustomizer) throws IOException
    {
        final Request baseRequest = Request.getBaseRequest(request);
        final HttpChannel httpChannel = baseRequest.getHttpChannel();
        final Connector connector = httpChannel.getConnector();

        if (negotiator == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no WebSocketNegotiator {}", baseRequest);
            return false;
        }

        if (!HttpMethod.GET.is(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded method!=GET {}", baseRequest);
            return false;
        }

        if (!HttpVersion.HTTP_1_1.equals(baseRequest.getHttpVersion()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded version!=1.1 {}", baseRequest);
            return false;
        }

        ByteBufferPool pool = negotiator.getByteBufferPool();
        if (pool == null)
            pool = baseRequest.getHttpChannel().getConnector().getByteBufferPool();

        Negotiation negotiation = new Negotiation(
            baseRequest,
            request,
            response,
            new WebSocketComponents());
        if (LOG.isDebugEnabled())
            LOG.debug("negotiation {}", negotiation);

        if (!negotiation.isUpgrade())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no upgrade header or connection upgrade", baseRequest);
            return false;
        }

        if (!WebSocketConstants.SPEC_VERSION_STRING.equals(negotiation.getVersion()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: unsupported version {} {}", negotiation.getVersion(), baseRequest);
            return false;
        }

        if (negotiation.getKey() == null)
            throw new BadMessageException("Missing request header 'Sec-WebSocket-Key'");

        // Negotiate the FrameHandler
        FrameHandler handler = negotiator.negotiate(negotiation);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiated handler {}", handler);

        // Handle error responses
        if (response.isCommitted())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: response committed {}", baseRequest);
            baseRequest.setHandled(true);
            return false;
        }
        if (response.getStatus() > 200)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: error sent {} {}", response.getStatus(), baseRequest);
            response.flushBuffer();
            baseRequest.setHandled(true);
            return false;
        }

        // Check for handler
        if (handler == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no frame handler provided {}", baseRequest);
            return false;
        }

        // validate negotiated subprotocol
        String subprotocol = negotiation.getSubprotocol();
        if (subprotocol != null)
        {
            if (!negotiation.getOfferedSubprotocols().contains(subprotocol))
                throw new WebSocketException("not upgraded: selected a subprotocol not present in offered subprotocols");
        }
        else
        {
            if (!negotiation.getOfferedSubprotocols().isEmpty())
                throw new WebSocketException("not upgraded: no subprotocol selected from offered subprotocols");
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

        Negotiated negotiated = new Negotiated(
            baseRequest.getHttpURI().toURI(),
            subprotocol,
            baseRequest.isSecure(),
            extensionStack,
            WebSocketConstants.SPEC_VERSION_STRING);

        // Create the Session
        WebSocketCoreSession coreSession = newWebSocketCoreSession(handler, negotiated);
        if (defaultCustomizer != null)
            defaultCustomizer.customize(coreSession);
        negotiator.customize(coreSession);

        if (LOG.isDebugEnabled())
            LOG.debug("session {}", coreSession);

        // Create a connection
        WebSocketConnection connection = newWebSocketConnection(httpChannel.getEndPoint(), connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), coreSession);
        if (LOG.isDebugEnabled())
            LOG.debug("connection {}", connection);
        if (connection == null)
            throw new WebSocketException("not upgraded: no connection");

        for (Connection.Listener listener : connector.getBeans(Connection.Listener.class))
        {
            connection.addListener(listener);
        }

        coreSession.setWebSocketConnection(connection);

        // send upgrade response
        Response baseResponse = baseRequest.getResponse();
        baseResponse.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        baseResponse.getHttpFields().put(UPGRADE_WEBSOCKET);
        baseResponse.getHttpFields().put(CONNECTION_UPGRADE);
        baseResponse.getHttpFields().put(HttpHeader.SEC_WEBSOCKET_ACCEPT, WebSocketCore.hashKey(negotiation.getKey()));

        // See bugs.eclipse.org/485969
        if (getSendServerVersion(connector))
        {
            baseResponse.getHttpFields().put(SERVER_VERSION);
        }

        baseResponse.flushBuffer();
        baseRequest.setHandled(true);

        // upgrade
        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={}", connection, coreSession);

        baseRequest.setAttribute(HttpTransport.UPGRADE_CONNECTION_ATTRIBUTE, connection);
        return true;
    }

    protected WebSocketCoreSession newWebSocketCoreSession(FrameHandler handler, Negotiated negotiated)
    {
        return new WebSocketCoreSession(handler, Behavior.SERVER, negotiated);
    }

    protected WebSocketConnection newWebSocketConnection(EndPoint endPoint, Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, WebSocketCoreSession coreSession)
    {
        return new WebSocketConnection(endPoint, executor, scheduler, byteBufferPool, coreSession);
    }

    private boolean getSendServerVersion(Connector connector)
    {
        ConnectionFactory connFactory = connector.getConnectionFactory(HttpVersion.HTTP_1_1.asString());
        if (connFactory == null)
            return false;

        if (connFactory instanceof HttpConnectionFactory)
        {
            HttpConfiguration httpConf = ((HttpConnectionFactory)connFactory).getHttpConfiguration();
            if (httpConf != null)
                return httpConf.getSendServerVersion();
        }
        return false;
    }
}
