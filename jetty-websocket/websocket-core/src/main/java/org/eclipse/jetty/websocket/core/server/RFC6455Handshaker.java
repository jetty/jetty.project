//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.handshake.AcceptHash;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;

public final class RFC6455Handshaker implements Handshaker
{
    static final Logger LOG = Log.getLogger(RFC6455Handshaker.class);
    private static HttpField UpgradeWebSocket = new PreEncodedHttpField(HttpHeader.UPGRADE, "WebSocket");
    private static HttpField ConnectionUpgrade = new PreEncodedHttpField(HttpHeader.CONNECTION,HttpHeader.UPGRADE.asString());

    public final static int VERSION = WebSocketConstants.SPEC_VERSION;

    public boolean upgradeRequest(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
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

        ServletContext context = baseRequest.getServletContext();
        HttpChannel channel = baseRequest.getHttpChannel();
        Connector connector = channel.getConnector();

        WebSocketSessionFactory sessionFactory = ContextConnectorConfiguration
                .lookup(WebSocketSessionFactory.class, context, connector);
        if (sessionFactory==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no session factory {}", baseRequest);
            return false;
        }

        WebSocketConnectionFactory connectionFactory = ContextConnectorConfiguration
                .lookup(WebSocketConnectionFactory.class, context, connector);
        if (connectionFactory==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no connection factory {}", baseRequest);
            return false;
        }


        boolean upgrade = false;
        QuotedCSV connectionCSVs = null;
        String key = null;
        QuotedCSV extensionCSVs = null;
        QuotedCSV subprotocolCSVs = null;

        for (HttpField field : baseRequest.getHttpFields())
        {
            if (field.getHeader()!=null)
            {
                switch(field.getHeader())
                {
                    case UPGRADE:
                        if (!"websocket".equalsIgnoreCase(field.getValue()))
                            return false;
                        upgrade = true;
                        break;

                    case CONNECTION:
                        if (connectionCSVs==null)
                            connectionCSVs = new QuotedCSV();
                        connectionCSVs.addValue(field.getValue().toLowerCase());
                        break;

                    case SEC_WEBSOCKET_KEY:
                        key = field.getValue();
                        break;

                    case SEC_WEBSOCKET_EXTENSIONS:
                        if (extensionCSVs==null)
                            extensionCSVs = new QuotedCSV();
                        extensionCSVs.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_SUBPROTOCOL:
                        if (subprotocolCSVs==null)
                            subprotocolCSVs = new QuotedCSV();
                        subprotocolCSVs.addValue(field.getValue());
                        break;

                    case SEC_WEBSOCKET_VERSION:
                        if (field.getIntValue()!=VERSION)
                            return false;
                        break;

                    default:
                }
            }
        }

        if (!upgrade)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no upgrade header {}", baseRequest);
            return false;
        }

        if (connectionCSVs==null || !connectionCSVs.getValues().contains("upgrade"))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no connection upgrade {}", baseRequest);
            return false;
        }

        if (key==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no key {}", baseRequest);
            return false;
        }

        List<ExtensionConfig> extensions = extensionCSVs == null
                ? Collections.emptyList()
                :extensionCSVs.getValues().stream().map(ExtensionConfig::parse).collect(Collectors.toList());
        List<String> subprotocols = subprotocolCSVs == null
                ? Collections.emptyList()
                :subprotocolCSVs.getValues();

        // Create a session using the default policy from the connector
        WebSocketCoreSession session = sessionFactory
                .newSession(baseRequest,
                            request,
                            connectionFactory.getPolicy(),
                            connectionFactory.getBufferPool(),
                            extensions,
                            subprotocols);
        if (session==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no session {}", baseRequest);
            return false;
        }
        if (session.getPolicy()==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no policy {}", baseRequest);
            return false;
        }
        if (subprotocols.size()>0 && session.getSubprotocol()==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no subprotocol from {} {}", subprotocols, baseRequest);
            return false;
        }

        // Create a connection
        WebSocketCoreConnection connection = connectionFactory.newConnection(connector,channel.getEndPoint(),session);
        if (connection==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no connection {}", baseRequest);
        }

        session.setWebSocketConnection(connection);

        // send upgrade response
        Response baseResponse = baseRequest.getResponse();
        baseResponse.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        baseResponse.getHttpFields().add(UpgradeWebSocket);
        baseResponse.getHttpFields().add(ConnectionUpgrade);
        baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_ACCEPT, AcceptHash.hashKey(key));
        baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,
                                         ExtensionConfig.toHeaderValue(session.getExtensionStack().getNegotiatedExtensions()));
        String subprotocol = session.getSubprotocol();
        if (subprotocol!=null)
            baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL,subprotocol);
        baseResponse.flushBuffer();
        baseRequest.setHandled(true);

        // upgrade
        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={}", connection, session);

        try
        {
            session.getExtensionStack().start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        baseRequest.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, connection);
        return true;
    }
}
