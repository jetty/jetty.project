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
import java.util.concurrent.Executor;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.NegotiateMessage;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.handshake.AcceptHash;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

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
        HttpChannel httpChannel = baseRequest.getHttpChannel();
        Connector connector = httpChannel.getConnector();

        FrameHandlerFactory channelFactory = ContextConnectorConfiguration
                .lookup(FrameHandlerFactory.class, context, connector);
        if (channelFactory==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no session factory {}", baseRequest);
            return false;
        }

        
        NegotiateMessage.Request upgradeRequest = new NegotiateMessage.Request(request.getMethod(),request.getRequestURI(),request.getProtocol());
        NegotiateMessage.Response upgradeResponse = new NegotiateMessage.Response();
        
        boolean version = false;
        boolean upgrade = false;
        QuotedCSV connectionCSVs = null;
        String key = null;

        for (HttpField field : baseRequest.getHttpFields())
        {
            upgradeRequest.addHeader(field.getName(),field.getValue());
            
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

                    case SEC_WEBSOCKET_VERSION:
                        if (field.getIntValue()!=VERSION)
                            return false;
                        version = true;
                        break;

                    default:
                }
            }
        }

        if (!version)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no version header {}", baseRequest);
            return false;
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

        // Create instance of policy that may be mutated by factory
        WebSocketPolicy policy =  ContextConnectorConfiguration
                .lookup(WebSocketPolicy.class, context, connector);
        policy = policy==null ? new WebSocketPolicy(WebSocketBehavior.SERVER)
                : policy.clonePolicy();
        
        
        // Negotiate the FrameHandler
        FrameHandler handler = channelFactory.newFrameHandler(
                upgradeRequest,
                upgradeResponse,
                policy,
                connector.getByteBufferPool());
        
        // update response headers
        upgradeResponse.getHeaders().entrySet().forEach(e->{e.getValue().forEach(v->response.addHeader(e.getKey(),v));});
        
        // Handle error responses
        if (upgradeResponse.isError())
        {
            baseRequest.setHandled(true);
            response.sendError(upgradeResponse.getErrorCode(),upgradeResponse.getErrorReason());
            return false;
        }
        
        // Check for handler
        if (handler==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no channel {}", baseRequest);
            return false;
        }

        // Check subprotocol negotiated
        String subprotocol = upgradeResponse.getSubprotocol();
        if (upgradeRequest.getOfferedSubprotocols().size()>0 && subprotocol==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no subprotocol from {} {}", upgradeRequest.getOfferedSubprotocols(), baseRequest);
            return false;
        }
        
        

        // Create the Channel
        WebSocketChannel channel = new WebSocketChannel(handler,policy,upgradeResponse.getExtensionStack(),subprotocol);
        
        // Create a connection
        WebSocketConnection connection = newWebSocketConnection(httpChannel.getEndPoint(),connector.getExecutor(),connector.getByteBufferPool(),channel);                
        if (connection==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no connection {}", baseRequest);
        }
        
        channel.setWebSocketConnection(connection);

        // send upgrade response
        Response baseResponse = baseRequest.getResponse();
        baseResponse.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        baseResponse.getHttpFields().add(UpgradeWebSocket);
        baseResponse.getHttpFields().add(ConnectionUpgrade);
        baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_ACCEPT, AcceptHash.hashKey(key));
        baseResponse.flushBuffer();
        baseRequest.setHandled(true);

        // upgrade
        if (LOG.isDebugEnabled())
            LOG.debug("upgrade connection={} session={}", connection, channel);

        baseRequest.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, connection);
        return true;
    }

    protected WebSocketConnection newWebSocketConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, WebSocketChannel wsChannel)
    {
        return new WebSocketConnection(endPoint,executor,byteBufferPool,wsChannel);
    }
}
