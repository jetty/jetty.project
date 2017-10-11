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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
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
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.eclipse.jetty.websocket.core.io.WebSocketRemoteEndpointImpl;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketConnectionFactory;
import org.eclipse.jetty.websocket.core.server.WebSocketSessionFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public class WebSocketHandler extends HandlerWrapper
{
    static final Logger LOG = Log.getLogger(WebSocketHandler.class);

    private static Handshaker RFC6455_Handshaker = new RFC6455Handshaker();


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

    static class TestWebSocketConnectionFactory extends ContainerLifeCycle implements WebSocketConnectionFactory
    {
        WebSocketExtensionRegistry extensionRegistry = new WebSocketExtensionRegistry();
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER,extensionRegistry);
        ByteBufferPool bufferPool = new MappedByteBufferPool();

        @Override
        public WebSocketPolicy getPolicy()
        {
            return policy;
        }

        @Override
        public WebSocketCoreConnection newConnection(Connector connector, EndPoint endPoint, WebSocketCoreSession session)
        {
            return new WebSocketCoreConnection(
                    endPoint,
                    connector.getExecutor(),
                    bufferPool,
                    session);
        }

        @Override
        public ByteBufferPool getBufferPool()
        {
            return bufferPool;
        }
    }

    static class TestWebSocketSessionFactory extends ContainerLifeCycle implements WebSocketSessionFactory
    {
        DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();

        @Override
        public WebSocketCoreSession newSession(Request baseRequest, ServletRequest request, WebSocketPolicy policy, ByteBufferPool bufferPool, List<ExtensionConfig> extensions, List<String> subprotocols)
        {
            // TODO abstract the creation of a local Endpoint
            WebSocketLocalEndpoint localEndpoint = new WebSocketLocalEndpoint.Adaptor()
            {
                WebSocketRemoteEndpoint remote;

                @Override
                public Logger getLog()
                {
                    return LOG;
                }

                @Override
                public boolean isOpen()
                {
                    return true; // TODO ???
                }

                @Override
                public void onOpen(WebSocketRemoteEndpoint remote)
                {
                    LOG.debug("onOpen {} {}", remote, this);
                    this.remote = remote;
                    TextFrame text = new TextFrame();
                    text.setPayload("Opened!");

                    remote.sendText("Opened!", new Callback()
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
                    remote.sendText("echo: "+text, callback);
                }
            };

            String subprotocol = subprotocols.isEmpty()?null:subprotocols.get(0);

            ExtensionStack extensionStack = new ExtensionStack(policy.getExtensionRegistry());
            extensionStack.negotiate(objectFactory, policy, bufferPool, extensions);

            WebSocketCoreSession session =
                    new WebSocketCoreSession(this,localEndpoint,policy,extensionStack,subprotocol);

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
