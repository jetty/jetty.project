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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class ContextHandlerCollectionTest
{
    @Test
    public void testVirtualHosts() throws Exception
    {
        Server server = new Server();
        LocalConnector connector0 = new LocalConnector(server);
        LocalConnector connector1 = new LocalConnector(server);
        connector1.setName("connector1");
        
        server.setConnectors(new Connector[]
        { connector0,connector1});

        ContextHandler contextA = new ContextHandler("/ctx");
        contextA.setVirtualHosts(new String[]
        { "www.example.com", "alias.example.com" });
        IsHandledHandler handlerA = new IsHandledHandler("A");
        contextA.setHandler(handlerA);
        contextA.setAllowNullPathInfo(true);

        ContextHandler contextB = new ContextHandler("/ctx");
        IsHandledHandler handlerB = new IsHandledHandler("B");
        contextB.setHandler(handlerB);
        contextB.setVirtualHosts(new String[]
        { "*.other.com" , "@connector1"});

        ContextHandler contextC = new ContextHandler("/ctx");
        IsHandledHandler handlerC = new IsHandledHandler("C");
        contextC.setHandler(handlerC);

        ContextHandler contextD = new ContextHandler("/");
        IsHandledHandler handlerD = new IsHandledHandler("D");
        contextD.setHandler(handlerD);
        
        ContextHandler contextE = new ContextHandler("/ctx/foo");
        IsHandledHandler handlerE = new IsHandledHandler("E");
        contextE.setHandler(handlerE);
        
        ContextHandler contextF = new ContextHandler("/ctxlong");
        IsHandledHandler handlerF = new IsHandledHandler("F");
        contextF.setHandler(handlerF);

        ContextHandlerCollection c = new ContextHandlerCollection();
        c.addHandler(contextA);
        c.addHandler(contextB);
        c.addHandler(contextC);
        
        HandlerList list = new HandlerList();
        list.addHandler(contextE);
        list.addHandler(contextF);
        list.addHandler(contextD);
        c.addHandler(list);
        
        server.setHandler(c);

        try
        {
            server.start();
            
            Object[][] tests = new Object[][] {
                {connector0,"www.example.com.", "/ctx",    handlerA},
                {connector0,"www.example.com.", "/ctx/",    handlerA},
                {connector0,"www.example.com.", "/ctx/info",    handlerA},
                {connector0,"www.example.com",  "/ctx/info",    handlerA},
                {connector0,"alias.example.com",  "/ctx/info",    handlerA},
                {connector1,"www.example.com.", "/ctx/info",    handlerA},
                {connector1,"www.example.com",  "/ctx/info",    handlerA},
                {connector1,"alias.example.com",  "/ctx/info",    handlerA},

                {connector1,"www.other.com",  "/ctx",    null},
                {connector1,"www.other.com",  "/ctx/",    handlerB},
                {connector1,"www.other.com",  "/ctx/info",    handlerB},
                {connector0,"www.other.com",  "/ctx/info",    handlerC},
                
                {connector0,"www.example.com",  "/ctxinfo",    handlerD},
                {connector1,"unknown.com",  "/unknown",    handlerD},
                
                {connector0,"alias.example.com",  "/ctx/foo/info",    handlerE},
                {connector0,"alias.example.com",  "/ctxlong/info",    handlerF},
            };
            
            for (int i=0;i<tests.length;i++)
            {
                Object[] test=tests[i];
                LocalConnector connector = (LocalConnector)test[0];
                String host=(String)test[1];
                String uri=(String)test[2];
                IsHandledHandler handler = (IsHandledHandler)test[3];

                handlerA.reset();
                handlerB.reset();
                handlerC.reset();
                handlerD.reset();
                handlerE.reset();
                handlerF.reset();

                String t = String.format("test   %d %s@%s --> %s | %s%n",i,uri,host,connector.getName(),handler);
                String response = connector.getResponses("GET "+uri+" HTTP/1.0\nHost: "+host+"\n\n");
                
                if (handler==null)
                {
                    Assert.assertThat(t,response,Matchers.containsString(" 302 "));
                }
                else
                {
                    assertThat(t,response,endsWith(handler.toString()));
                    if (!handler.isHandled())
                    {
                        System.err.printf("FAILED %s",t);
                        System.err.println(response);
                        Assert.fail();
                    }
                }
            }

        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testVirtualHostWildcard() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[] { connector });

        ContextHandler context = new ContextHandler("/");

        IsHandledHandler handler = new IsHandledHandler("H");
        context.setHandler(handler);

        ContextHandlerCollection c = new ContextHandlerCollection();
        c.addHandler(context);

        server.setHandler(c);

        try
        {
            server.start();
            checkWildcardHost(true,server,null,new String[] {"example.com", ".example.com", "vhost.example.com"});
            checkWildcardHost(false,server,new String[] {null},new String[] {"example.com", ".example.com", "vhost.example.com"});

            checkWildcardHost(true,server,new String[] {"example.com", "*.example.com"}, new String[] {"example.com", ".example.com", "vhost.example.com"});
            checkWildcardHost(false,server,new String[] {"example.com", "*.example.com"}, new String[] {"badexample.com", ".badexample.com", "vhost.badexample.com"});

            checkWildcardHost(false,server,new String[] {"*."}, new String[] {"anything.anything"});

            checkWildcardHost(true,server,new String[] {"*.example.com"}, new String[] {"vhost.example.com", ".example.com"});
            checkWildcardHost(false,server,new String[] {"*.example.com"}, new String[] {"vhost.www.example.com", "example.com", "www.vhost.example.com"});

            checkWildcardHost(true,server,new String[] {"*.sub.example.com"}, new String[] {"vhost.sub.example.com", ".sub.example.com"});
            checkWildcardHost(false,server,new String[] {"*.sub.example.com"}, new String[] {".example.com", "sub.example.com", "vhost.example.com"});

            checkWildcardHost(false,server,new String[] {"example.*.com","example.com.*"}, new String[] {"example.vhost.com", "example.com.vhost", "example.com"});
        }
        finally
        {
            server.stop();
        }
    }

    private void checkWildcardHost(boolean succeed, Server server, String[] contextHosts, String[] requestHosts) throws Exception
    {
        LocalConnector connector = (LocalConnector)server.getConnectors()[0];
        ContextHandlerCollection handlerCollection = (ContextHandlerCollection)server.getHandler();
        ContextHandler context = (ContextHandler)handlerCollection.getHandlers()[0];
        IsHandledHandler handler = (IsHandledHandler)context.getHandler();

        context.setVirtualHosts(contextHosts);
        // trigger this manually; it's supposed to be called when adding the handler
        handlerCollection.mapContexts();

        for(String host : requestHosts)
        {
            // System.err.printf("host=%s in %s%n",host,contextHosts==null?Collections.emptyList():Arrays.asList(contextHosts));
            
            String response=connector.getResponse("GET / HTTP/1.0\n" + "Host: "+host+"\nConnection:close\n\n");
            // System.err.println(response);
            if(succeed)
                assertTrue("'"+host+"' should have been handled.",handler.isHandled());
            else
                assertFalse("'"+host + "' should not have been handled.", handler.isHandled());
            handler.reset();
        }

    }


    @Test
    public void testFindContainer() throws Exception
    {
        Server server = new Server();

        ContextHandler contextA = new ContextHandler("/a");
        IsHandledHandler handlerA = new IsHandledHandler("A");
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/b");
        IsHandledHandler handlerB = new IsHandledHandler("B");
        HandlerWrapper wrapperB = new HandlerWrapper();
        wrapperB.setHandler(handlerB);
        contextB.setHandler(wrapperB);

        ContextHandler contextC = new ContextHandler("/c");
        IsHandledHandler handlerC = new IsHandledHandler("C");
        contextC.setHandler(handlerC);

        ContextHandlerCollection collection = new ContextHandlerCollection();

        collection.addHandler(contextA);
        collection.addHandler(contextB);
        collection.addHandler(contextC);

        HandlerWrapper wrapper = new HandlerWrapper();
        wrapper.setHandler(collection);
        server.setHandler(wrapper);

        assertEquals(wrapper,AbstractHandlerContainer.findContainerOf(server,HandlerWrapper.class,handlerA));
        assertEquals(contextA,AbstractHandlerContainer.findContainerOf(server,ContextHandler.class,handlerA));
        assertEquals(contextB,AbstractHandlerContainer.findContainerOf(server,ContextHandler.class,handlerB));
        assertEquals(wrapper,AbstractHandlerContainer.findContainerOf(server,HandlerWrapper.class,handlerB));
        assertEquals(contextB,AbstractHandlerContainer.findContainerOf(collection,HandlerWrapper.class,handlerB));
        assertEquals(wrapperB,AbstractHandlerContainer.findContainerOf(contextB,HandlerWrapper.class,handlerB));

    }
    
    @Test
    public void testWrappedContext() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[] { connector });

        ContextHandler root = new ContextHandler("/");
        root.setHandler(new IsHandledHandler("root"));
        
        ContextHandler left = new ContextHandler("/left");
        left.setHandler(new IsHandledHandler("left"));
        
        HandlerList centre = new HandlerList();
        ContextHandler centreLeft = new ContextHandler("/leftcentre");
        centreLeft.setHandler(new IsHandledHandler("left of centre"));
        ContextHandler centreRight = new ContextHandler("/rightcentre");
        centreRight.setHandler(new IsHandledHandler("right of centre"));
        centre.setHandlers(new Handler[]{centreLeft,new WrappedHandler(centreRight)});
        
        ContextHandler right = new ContextHandler("/right");
        right.setHandler(new IsHandledHandler("right"));
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]{root,left,centre,new WrappedHandler(right)});
        
        server.setHandler(contexts);
        server.start();
        
        String response=connector.getResponses("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("root"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /foobar/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("root"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /left/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("left"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /leftcentre/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("left of centre"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /rightcentre/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("right of centre"));
        assertThat(response, containsString("Wrapped: TRUE"));

        response=connector.getResponses("GET /right/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("right"));
        assertThat(response, containsString("Wrapped: TRUE"));
    }


    @Test
    public void testAsyncWrappedContext() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[] { connector });

        ContextHandler root = new ContextHandler("/");
        root.setHandler(new AsyncHandler("root"));
        
        ContextHandler left = new ContextHandler("/left");
        left.setHandler(new AsyncHandler("left"));
        
        HandlerList centre = new HandlerList();
        ContextHandler centreLeft = new ContextHandler("/leftcentre");
        centreLeft.setHandler(new AsyncHandler("left of centre"));
        ContextHandler centreRight = new ContextHandler("/rightcentre");
        centreRight.setHandler(new AsyncHandler("right of centre"));
        centre.setHandlers(new Handler[]{centreLeft,new WrappedHandler(centreRight)});
        
        ContextHandler right = new ContextHandler("/right");
        right.setHandler(new AsyncHandler("right"));
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]{root,left,centre,new WrappedHandler(right)});
        
        server.setHandler(contexts);
        server.start();
        
        String response=connector.getResponses("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("root"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /foobar/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("root"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /left/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("left"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /leftcentre/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("left of centre"));
        assertThat(response, not(containsString("Wrapped: TRUE")));

        response=connector.getResponses("GET /rightcentre/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("right of centre"));
        assertThat(response, containsString("Wrapped: ASYNC"));

        response=connector.getResponses("GET /right/info HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, endsWith("right"));
        assertThat(response, containsString("Wrapped: ASYNC"));
    }
    
    
    private static final class WrappedHandler extends HandlerWrapper
    {
        WrappedHandler(Handler handler)
        {
            setHandler(handler);
        }
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (response.containsHeader("Wrapped"))
                response.setHeader("Wrapped", "ASYNC");
            else
                response.setHeader("Wrapped", "TRUE");
            super.handle(target, baseRequest, request, response);
        }   
    }
    
    
    private static final class IsHandledHandler extends AbstractHandler
    {
        private boolean handled;
        private final String name;

        public IsHandledHandler(String string)
        {
            name=string;
        }

        public boolean isHandled()
        {
            return handled;
        }

        @Override
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            this.handled = true;
            response.getWriter().print(name);
        }

        public void reset()
        {
            handled = false;
        }
        
        @Override
        public String toString()
        {
            return name;
        }
    }


    
    private static final class AsyncHandler extends AbstractHandler
    {
        private final String name;

        public AsyncHandler(String string)
        {
            name=string;
        }

        @Override
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            
            String n = (String)baseRequest.getAttribute("async");
            if (n==null)
            {
                AsyncContext async=baseRequest.startAsync();
                async.setTimeout(1000);
                baseRequest.setAttribute("async", name);
                async.dispatch();
            }
            else
            {
                response.getWriter().print(n);
            }
        }
        
        @Override
        public String toString()
        {
            return name;
        }
    }


}
