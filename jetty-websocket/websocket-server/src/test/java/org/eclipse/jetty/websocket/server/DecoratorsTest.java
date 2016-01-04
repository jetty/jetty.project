//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class DecoratorsTest
{
    private static class DecoratorsSocket extends WebSocketAdapter
    {
        private final DecoratedObjectFactory objFactory;
        
        public DecoratorsSocket(DecoratedObjectFactory objFactory)
        {
            this.objFactory = objFactory;
        }
        
        @Override
        public void onWebSocketText(String message)
        {
            StringWriter str = new StringWriter();
            PrintWriter out = new PrintWriter(str);
            
            if (objFactory != null)
            {
                out.printf("Object is a DecoratedObjectFactory%n");
                List<Decorator> decorators = objFactory.getDecorators();
                out.printf("Decorators.size = [%d]%n",decorators.size());
                for (Decorator decorator : decorators)
                {
                    out.printf(" decorator[] = %s%n",decorator.getClass().getName());
                }
            }
            else
            {
                out.printf("DecoratedObjectFactory is NULL%n");
            }

            getRemote().sendStringByFuture(str.toString());
        }
    }

    private static class DecoratorsCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            ServletContext servletContext = req.getHttpServletRequest().getServletContext();
            DecoratedObjectFactory objFactory = (DecoratedObjectFactory)servletContext.getAttribute(DecoratedObjectFactory.ATTR);
            return new DecoratorsSocket(objFactory);
        }
    }

    public static class DecoratorsRequestServlet extends WebSocketServlet
    {
        private static final long serialVersionUID = 1L;
        private final WebSocketCreator creator;

        public DecoratorsRequestServlet(WebSocketCreator creator)
        {
            this.creator = creator;
        }

        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this.creator);
        }
    }
    
    private static class DummyUtilDecorator implements org.eclipse.jetty.util.Decorator
    {
        @Override
        public <T> T decorate(T o)
        {
            return o;
        }

        @Override
        public void destroy(Object o)
        {
        }
    }

    private static SimpleServletServer server;
    private static DecoratorsCreator decoratorsCreator;

    @BeforeClass
    public static void startServer() throws Exception
    {
        decoratorsCreator = new DecoratorsCreator();
        server = new SimpleServletServer(new DecoratorsRequestServlet(decoratorsCreator))
        {
            @Override
            protected void configureServletContextHandler(ServletContextHandler context)
            {
                // Add decorator in the new util way
                context.getObjectFactory().clear();
                context.getObjectFactory().addDecorator(new DummyUtilDecorator());
            }
        };
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    public void testAccessRequestCookies() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setTimeout(1,TimeUnit.SECONDS);

        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
            
            client.write(new TextFrame().setPayload("info"));

            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame resp = frames.poll();
            String textMsg = resp.getPayloadAsUTF8();
            
            assertThat("DecoratedObjectFactory", textMsg, containsString("Object is a DecoratedObjectFactory"));
            assertThat("decorators.size", textMsg, containsString("Decorators.size = [1]"));
            assertThat("decorator type", textMsg, containsString("decorator[] = " + DummyUtilDecorator.class.getName()));
        }
        finally
        {
            client.close();
        }
    }
}
