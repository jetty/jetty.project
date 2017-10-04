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

package org.eclipse.jetty.websocket.testimpl;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.ParserDeMasker;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.handshake.AcceptHash;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.eclipse.jetty.websocket.core.io.WebSocketRemoteEndpointImpl;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class WebSocketHandler extends HandlerWrapper
{
    static final Logger LOG = Log.getLogger(WebSocketHandler.class);

    private static HttpField UpgradeWebSocket = new PreEncodedHttpField(HttpHeader.UPGRADE,"WebSocket");
    private static HttpField ConnectionUpgrade = new PreEncodedHttpField(HttpHeader.CONNECTION,HttpHeader.UPGRADE.asString());
    private static Handshaker RFC6455_Handshaker = new RFC6455Handshaker();


    public interface Handshaker
    {
        boolean upgradeRequest(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    interface WebSocketConnectionFactory extends ConnectionFactory
    {
        WebSocketCoreConnection newConnection(Connector connector, EndPoint endPoint, List<String> extensions);

        @Override
        default Connection newConnection(Connector connector, EndPoint endPoint)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        default String getProtocol()
        {
            return "ws";
        }

        @Override
        default List<String> getProtocols()
        {
            return Collections.singletonList(getProtocol());
        }
    }

    interface WebSocketSessionFactory
    {
        // TODO why is core session abstract?
        WebSocketCoreSession<ContainerLifeCycle,WebSocketCoreConnection,WebSocketLocalEndpoint,WebSocketRemoteEndpoint>
        newSession(WebSocketCoreConnection connection, String subprotocol);
    }


    public WebSocketHandler()
    {
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Handshaker handshaker = getHandshaker(baseRequest);
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} handshaker={}",baseRequest,handshaker);
        if (handshaker!=null && handshaker.upgradeRequest(baseRequest, request, response))
            return;
        super.handle(target,baseRequest,request,response);
    }


    protected Handshaker getHandshaker(Request baseRequest)
    {
        // TODO this can eventually be made pluggable
        HttpField version = baseRequest.getHttpFields().getField(HttpHeader.SEC_WEBSOCKET_VERSION);
        if (version !=null && version.getIntValue()==RFC6455Handshaker.VERSION)
            return RFC6455_Handshaker;
        return null;
    }


    private final static class RFC6455Handshaker implements Handshaker
    {

        final static int VERSION = 13;

        public boolean upgradeRequest(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (!HttpMethod.GET.is(request.getMethod()))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded method!=GET {}",baseRequest);
                return false;
            }

            if (!HttpVersion.HTTP_1_1.equals(baseRequest.getHttpVersion()))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded version!=1.1 {}",baseRequest);
                return false;
            }

            boolean upgrade = false;
            QuotedCSV connectionCSVs = null;
            String key = null;
            QuotedCSV extensionCSVs = null;
            String subprotocol = null;

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
                            if (subprotocol!=null)
                                return false;
                            subprotocol = field.getValue();
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
                    LOG.debug("not upgraded no upgrade header {}",baseRequest);
                return false;
            }

            if (connectionCSVs==null || !connectionCSVs.getValues().contains("upgrade"))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded no connection upgrade {}",baseRequest);
                return false;
            }

            if (key==null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded no key {}",baseRequest);
                return false;
            }

            // Create a connection from a connection factory (from context or connector)
            ServletContext context = baseRequest.getServletContext();
            WebSocketConnectionFactory connectionFactory = null;
            if (context!=null)
                connectionFactory = (WebSocketConnectionFactory)context.getAttribute(WebSocketConnectionFactory.class.getName());
            if (connectionFactory==null)
                connectionFactory = baseRequest.getHttpChannel().getConnector().getBean(WebSocketConnectionFactory.class);
            if (connectionFactory==null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded no connection factory {}",baseRequest);
                return false;
            }
            HttpChannel channel = baseRequest.getHttpChannel();
            WebSocketCoreConnection connection =
                    connectionFactory.newConnection(channel.getConnector(),channel.getEndPoint(),extensionCSVs.getValues());
            if (connection==null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded no connection {}",baseRequest);
            }

            // Create a session from a session factory (from context or connector)
            WebSocketSessionFactory sessionFactory = null;
            if (context!=null)
                sessionFactory = (WebSocketSessionFactory)context.getAttribute(WebSocketSessionFactory.class.getName());
            if (sessionFactory==null)
                sessionFactory = baseRequest.getHttpChannel().getConnector().getBean(WebSocketSessionFactory.class);
            if (sessionFactory==null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded no session factory {}",baseRequest);
                return false;
            }
            WebSocketCoreSession session = sessionFactory.newSession(connection, subprotocol);
            if (session==null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("not upgraded no session {}",baseRequest);
            }

            connection.setSession(session);

            // send upgrade response
            Response baseResponse = baseRequest.getResponse();
            baseResponse.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            baseResponse.getHttpFields().add(UpgradeWebSocket);
            baseResponse.getHttpFields().add(ConnectionUpgrade);
            baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_ACCEPT, AcceptHash.hashKey(key));
            baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS,
                                             ExtensionConfig.toHeaderValue(connection.getExtensionStack().getNegotiatedExtensions()));
            if (subprotocol!=null)
                baseResponse.getHttpFields().add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL,subprotocol);
            baseResponse.flushBuffer();
            baseRequest.setHandled(true);

            // upgrade
            if (LOG.isDebugEnabled())
                LOG.debug("upgrade connection={} session={}",connection,session);

            try
            {
                connection.getExtensionStack().start();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            baseRequest.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, connection);
            return true;
        }
    };



    static class TestWebSocketConnectionFactory extends ContainerLifeCycle implements WebSocketConnectionFactory
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ByteBufferPool bufferPool = new MappedByteBufferPool();
        WebSocketExtensionRegistry extensionRegistry = new WebSocketExtensionRegistry();
        DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();

        @Override
        public WebSocketCoreConnection newConnection(Connector connector, EndPoint endPoint, List<String> extensions)
        {
            ExtensionStack extensionStack = new ExtensionStack(extensionRegistry);
            extensionStack.negotiate(objectFactory, policy, bufferPool,
                                     extensions.stream().map(ExtensionConfig::parse).collect(Collectors.toList()));
            UpgradeRequest upgradeRequest = null; // TODO
            UpgradeResponse upgradeResponse = null; // TODO

            return new WebSocketCoreConnection(
                    endPoint,
                    connector.getExecutor(),
                    bufferPool,
                    objectFactory,
                    policy,
                    extensionStack,
                    upgradeRequest,
                    upgradeResponse);
        }
    }


    static class TestWebSocketSessionFactory extends ContainerLifeCycle implements WebSocketSessionFactory
    {
        @Override
        public WebSocketCoreSession<ContainerLifeCycle,WebSocketCoreConnection,WebSocketLocalEndpoint,WebSocketRemoteEndpoint>
        newSession(WebSocketCoreConnection connection, String subprotocol)
        {
            // TODO why is remoteEndpoint an interface rather than just an impl?
            WebSocketRemoteEndpointImpl remoteEndpoint = new WebSocketRemoteEndpointImpl(connection);

            // TODO abstract the creation of a local Endpoint
            WebSocketLocalEndpoint localEndpoint = new WebSocketLocalEndpoint()
            {
                @Override
                public Logger getLog()
                {
                    return LOG;
                }

                @Override
                public boolean isOpen()
                {
                    return connection.isOpen();
                }

                @Override
                public void onOpen()
                {
                    LOG.debug("onOpen {}",this);
                    TextFrame text = new TextFrame();
                    text.setPayload("Opened!");

                    // TODO do I need to mask this myself? if so then why?
                    remoteEndpoint.sendFrame(text, new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            LOG.debug("onOpen write!");
                        }

                        @Override
                        public void failed(Throwable x)
                        {
                            LOG.warn(x);
                        }
                    });
                }

                @Override
                public void onText(Frame frame, Callback callback)
                {
                    ByteBuffer payload = frame.getPayload();
                    String text = BufferUtil.toUTF8String(payload);

                    LOG.debug("onText {} / {}",text,frame);
                    // TODO why not constructor injection on TextFrame?
                    TextFrame textFrame = new TextFrame();

                    // TODO why not String getter or ByteBuffer setter?
                    textFrame.setPayload("echo: "+text);
                    remoteEndpoint.sendFrame(textFrame, callback);
                }
            };

            WebSocketCoreSession<ContainerLifeCycle,WebSocketCoreConnection,WebSocketLocalEndpoint,WebSocketRemoteEndpoint> session =
                    new WebSocketCoreSession(this,connection)
                    {
                        @Override
                        public void open()
                        {
                            ((WebSocketRemoteEndpointImpl)remoteEndpoint).open();
                            super.open();
                        }
                        // TODO why is core session abstract?
                    };

            //TODO Why can't this be done in constructor?
            //TODO Why does session need the endpoint object?
            session.setWebSocketEndpoint(null,connection.getPolicy(),localEndpoint,remoteEndpoint);

            return session;
        }
    }

    public static void main(String... arg) throws Exception
    {
        Server server = new Server();

        ServerConnector connector = new ServerConnector(
                server,
                new HttpConnectionFactory(),
                new TestWebSocketConnectionFactory()
        );
        connector.setPort(8080);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);
        context.setAttribute(WebSocketSessionFactory.class.getName(),new TestWebSocketSessionFactory());

        WebSocketHandler handler = new WebSocketHandler();
        context.setHandler(handler);
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().println("Hello World!");
                baseRequest.setHandled(true);
            }
        });

        server.start();
        server.join();
    }
}
