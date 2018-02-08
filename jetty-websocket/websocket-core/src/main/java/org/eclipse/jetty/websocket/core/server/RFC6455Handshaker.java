//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AcceptHash;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

public final class RFC6455Handshaker implements Handshaker
{
    static final Logger LOG = Log.getLogger(RFC6455Handshaker.class);
    private static final HttpField UPGRADE_WEBSOCKET = new PreEncodedHttpField(HttpHeader.UPGRADE, "WebSocket");
    private static final HttpField CONNECTION_UPGRADE = new PreEncodedHttpField(HttpHeader.CONNECTION,HttpHeader.UPGRADE.asString());
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);

    public final static int VERSION = WebSocketConstants.SPEC_VERSION;

    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // TODO reduce the debug from this class
        
        Request baseRequest = Request.getBaseRequest(request);
        HttpChannel httpChannel = baseRequest.getHttpChannel();
        Connector connector = httpChannel.getConnector();
        
        if (negotiator==null)
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


        Negotiation negotiation = new Negotiation(baseRequest,request,response);
        if (LOG.isDebugEnabled())
            LOG.debug("negotiation {}", negotiation);
        
        if (!negotiation.isUpgrade())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: no upgrade header or connection upgrade", baseRequest);
            return false;
        }
        
        if (negotiation.getVersion()!=VERSION)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: unsupported version {} {}", negotiation.getVersion(), baseRequest);
            return false;
        }

        if (negotiation.getKey()==null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded no key {}", baseRequest);
            return false;
        }

        // Create instance of policy that may be mutated by negotiation
        WebSocketPolicy policy = negotiator.getCandidatePolicy();
        if (policy==null)
            policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        negotiation.setPolicy(policy);
        
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
        if (response.getStatus()>200)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("not upgraded: error sent {} {}",response.getStatus(), baseRequest);
            response.flushBuffer();
            baseRequest.setHandled(true);
            return false;
        }

        // Check for handler
        if (handler==null)
        {
            LOG.warn("not upgraded: no channel {}", baseRequest);
            return false;
        }

        // Update policy
        policy = negotiation.getPolicy();
        if (policy==null)
            policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        
        // Check if subprotocol negotiated
        String subprotocol = negotiation.getSubprotocol();
        if (negotiation.getOfferedSubprotocols().size()>0)
        {
            if(subprotocol == null)
            {
                // TODO: this message needs to be returned to Http Client
                LOG.warn("not upgraded: no subprotocol selected from offered subprotocols {}: {}", negotiation.getOfferedSubprotocols(), baseRequest);
                return false;
            }

            if(!negotiation.getOfferedSubprotocols().contains(subprotocol))
            {
                // TODO: this message needs to be returned to Http Client
                LOG.warn("not upgraded: selected subprotocol {} not present in offered subprotocols {}: {}", subprotocol, negotiation.getOfferedSubprotocols(), baseRequest);
                return false;
            }
        }
        
        // Set up extensions
        ExtensionStack extensionStack;
        List<ExtensionConfig> offeredExtensions = negotiation.getOfferedExtensions();
        if (baseRequest.getResponse().getHttpFields().contains(HttpHeader.SEC_WEBSOCKET_EXTENSIONS))
        {
            // Replace offeredExtensions with application extensions
            // TODO check if this is correct
            List<ExtensionConfig> applicationExtensions = new ArrayList<>(offeredExtensions.size());
            for (String ext : baseRequest.getResponse().getHttpFields().getCSV(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,false))            
            {
                Optional<ExtensionConfig> config = offeredExtensions.stream().filter(c->c.getName().equalsIgnoreCase(ext)).findFirst();
                if (config.isPresent())
                    applicationExtensions.add(config.get());
                else
                    applicationExtensions.add(ExtensionConfig.parse(ext));
            }
            offeredExtensions = applicationExtensions;
        }
        
        extensionStack = new ExtensionStack(negotiator.getExtensionRegistry());
        extensionStack.negotiate(negotiator.getObjectFactory(), policy, negotiator.getByteBufferPool(), offeredExtensions);
        if (LOG.isDebugEnabled())
            LOG.debug("extensions {}", extensionStack);
        if (extensionStack.hasNegotiatedExtensions())
            response.setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),
                    ExtensionConfig.toHeaderValue(extensionStack.getNegotiatedExtensions()));
        else
            response.setHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),null);
        
        // Create the Channel
        WebSocketChannel channel = new WebSocketChannel(handler,policy,extensionStack,subprotocol);
        if (LOG.isDebugEnabled())
            LOG.debug("channel {}", channel);
        
        // Create a connection
        WebSocketConnection connection = newWebSocketConnection(httpChannel.getEndPoint(),connector.getExecutor(),connector.getByteBufferPool(),channel);  
        if (LOG.isDebugEnabled())
            LOG.debug("connection {}", connection);
        if (connection==null)
        {
            LOG.warn("not upgraded: no connection {}", baseRequest);
            return false;
        }
        
        channel.setWebSocketConnection(connection);

        // send upgrade response
        Response baseResponse = baseRequest.getResponse();
        baseResponse.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        baseResponse.getHttpFields().put(UPGRADE_WEBSOCKET);
        baseResponse.getHttpFields().put(CONNECTION_UPGRADE);
        baseResponse.getHttpFields().put(HttpHeader.SEC_WEBSOCKET_ACCEPT, AcceptHash.hashKey(negotiation.getKey()));

        // See bugs.eclipse.org/485969
        if (getSendServerVersion(connector))
        {
            baseResponse.getHttpFields().put(SERVER_VERSION);
        }

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

    private boolean getSendServerVersion(Connector connector)
    {
        ConnectionFactory connFactory = connector.getConnectionFactory(HttpVersion.HTTP_1_1.asString());
        if (connFactory == null)
            return false;

        if (connFactory instanceof HttpConnectionFactory)
        {
            HttpConfiguration httpConf = ((HttpConnectionFactory) connFactory).getHttpConfiguration();
            if (httpConf != null)
                return httpConf.getSendServerVersion();
        }
        return false;
    }
}
