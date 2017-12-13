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

package org.eclipse.jetty.server;
  
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class NotAcceptingTest
{
    Server server;

    @Before
    public void before()
    {
        server = new Server();
    }

    @After
    public void after() throws Exception
    {
        server.stop();
        server=null;
    }

    @Test
    public void testServerConnectorBlockingAccept() throws Exception
    {
        ServerConnector connector = new ServerConnector(server,1,1);
        connector.setPort(0);
        connector.setIdleTimeout(500);
        connector.setAcceptQueueSize(10);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        try(Socket client0 = new Socket("localhost",connector.getLocalPort());)
        {
            HttpTester.Input in0 = HttpTester.from(client0.getInputStream());

            client0.getOutputStream().write("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            String uri = handler.exchange.exchange("data");
            assertThat(uri,is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("data"));
            
            connector.setAccepting(false);

            // 0th connection still working
            client0.getOutputStream().write("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            uri = handler.exchange.exchange("more data");
            assertThat(uri,is("/two"));
            response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("more data"));
            

            try(Socket client1 = new Socket("localhost",connector.getLocalPort());)
            {
                // can't stop next connection being accepted
                HttpTester.Input in1 = HttpTester.from(client1.getInputStream());
                client1.getOutputStream().write("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                uri = handler.exchange.exchange("new connection");
                assertThat(uri,is("/three"));
                response = HttpTester.parseResponse(in1);
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("new connection"));
                

                try(Socket client2 = new Socket("localhost",connector.getLocalPort());)
                {

                    HttpTester.Input in2 = HttpTester.from(client2.getInputStream());
                    client2.getOutputStream().write("GET /four HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());

                    try
                    {
                        uri = handler.exchange.exchange("delayed connection",500,TimeUnit.MILLISECONDS);
                        Assert.fail(uri);
                    }
                    catch(TimeoutException e)
                    {
                        // Can we accept the original?
                        connector.setAccepting(true); 
                        uri = handler.exchange.exchange("delayed connection");
                        assertThat(uri,is("/four"));
                        response = HttpTester.parseResponse(in2);
                        assertThat(response.getStatus(),is(200));
                        assertThat(response.getContent(),is("delayed connection"));
                    }
                }
            }
        }
    }
    

    @Test
    public void testLocalConnector() throws Exception
    {
        LocalConnector connector = new LocalConnector(server);
        connector.setIdleTimeout(500);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        try(LocalEndPoint client0 = connector.connect())
        {
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            String uri = handler.exchange.exchange("data");
            assertThat(uri,is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("data"));
            
            connector.setAccepting(false);

            // 0th connection still working
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            uri = handler.exchange.exchange("more data");
            assertThat(uri,is("/two"));
            response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("more data"));
            

            try(LocalEndPoint client1 = connector.connect())
            {
                // can't stop next connection being accepted
                client1.addInputAndExecute(BufferUtil.toBuffer("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n"));
                uri = handler.exchange.exchange("new connection");
                assertThat(uri,is("/three"));
                response = HttpTester.parseResponse(client1.getResponse());
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("new connection"));
                

                try(LocalEndPoint client2 = connector.connect())
                {
                    client2.addInputAndExecute(BufferUtil.toBuffer("GET /four HTTP/1.1\r\nHost:localhost\r\n\r\n"));

                    try
                    {
                        uri = handler.exchange.exchange("delayed connection",500,TimeUnit.MILLISECONDS);
                        Assert.fail(uri);
                    }
                    catch(TimeoutException e)
                    {
                        // Can we accept the original?
                        connector.setAccepting(true); 
                        uri = handler.exchange.exchange("delayed connection",10,TimeUnit.SECONDS);
                        assertThat(uri,is("/four"));
                        response = HttpTester.parseResponse(client2.getResponse());
                        assertThat(response.getStatus(),is(200));
                        assertThat(response.getContent(),is("delayed connection"));
                    }
                }
            }
        }
    }
   
    @Test
    public void testServerConnectorAsyncAccept() throws Exception
    {
        ServerConnector connector = new ServerConnector(server,0,1);
        connector.setPort(0);
        connector.setIdleTimeout(500);
        connector.setAcceptQueueSize(10);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        try(Socket client0 = new Socket("localhost",connector.getLocalPort());)
        {
            HttpTester.Input in0 = HttpTester.from(client0.getInputStream());

            client0.getOutputStream().write("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            String uri = handler.exchange.exchange("data");
            assertThat(uri,is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("data"));
            
            connector.setAccepting(false);

            // 0th connection still working
            client0.getOutputStream().write("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            uri = handler.exchange.exchange("more data");
            assertThat(uri,is("/two"));
            response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("more data"));
            

            try(Socket client1 = new Socket("localhost",connector.getLocalPort());)
            {
                HttpTester.Input in1 = HttpTester.from(client1.getInputStream());
                client1.getOutputStream().write("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                
                try
                {
                    uri = handler.exchange.exchange("delayed connection",500,TimeUnit.MILLISECONDS);
                    Assert.fail(uri);
                }
                catch(TimeoutException e)
                {
                    // Can we accept the original?
                    connector.setAccepting(true); 
                    uri = handler.exchange.exchange("delayed connection");
                    assertThat(uri,is("/three"));
                    response = HttpTester.parseResponse(in1);
                    assertThat(response.getStatus(),is(200));
                    assertThat(response.getContent(),is("delayed connection"));
                }
            }
        }
    } 
    
    public static class TestHandler extends AbstractHandler
    {
        final Exchanger<String> exchange = new Exchanger<>();
        transient int handled;
        
        public TestHandler()
        {
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String content = exchange.exchange(baseRequest.getRequestURI());
                baseRequest.setHandled(true);
                handled++;
                response.setContentType("text/html;charset=utf-8");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().print(content);
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
        }
        
        public int getHandled()
        {
            return handled;
        }
    }
    
    @Test
    public void testConnectionLimit() throws Exception
    {
        Server server = new Server();
        server.addBean(new ConnectionLimit(9,server));
        server.setHandler(new HelloHandler());

        LocalConnector localConnector = new LocalConnector(server);
        localConnector.setIdleTimeout(60000);
        server.addConnector(localConnector);
        
        ServerConnector blockingConnector = new ServerConnector(server,1,1);
        blockingConnector.setPort(0);
        blockingConnector.setIdleTimeout(60000);
        blockingConnector.setAcceptQueueSize(10);
        server.addConnector(blockingConnector);
        
        ServerConnector asyncConnector = new ServerConnector(server,0,1);
        asyncConnector.setPort(0);
        asyncConnector.setIdleTimeout(60000);
        asyncConnector.setAcceptQueueSize(10);
        server.addConnector(asyncConnector);

        server.start();
        
        try (
            LocalEndPoint local0 = localConnector.connect();
            LocalEndPoint local1 = localConnector.connect();
            LocalEndPoint local2 = localConnector.connect();
            Socket blocking0 = new Socket("localhost",blockingConnector.getLocalPort());
            Socket blocking1 = new Socket("localhost",blockingConnector.getLocalPort());
            Socket blocking2 = new Socket("localhost",blockingConnector.getLocalPort());
            Socket async0 = new Socket("localhost",asyncConnector.getLocalPort());
            Socket async1 = new Socket("localhost",asyncConnector.getLocalPort());
            Socket async2 = new Socket("localhost",asyncConnector.getLocalPort());
            )
        {
            for (LocalEndPoint client: new LocalEndPoint[] {local0,local1,local2})
            {
                client.addInputAndExecute(BufferUtil.toBuffer("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n"));
                HttpTester.Response response = HttpTester.parseResponse(client.getResponse());
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("Hello\n"));
            }
            
            for (Socket client : new Socket[]{blocking0,blocking1,blocking2,async0,async1,async2})
            {
                HttpTester.Input in = HttpTester.from(client.getInputStream());
                client.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("Hello\n"));
            }
            
            assertThat(localConnector.isAccepting(),is(false));
            assertThat(blockingConnector.isAccepting(),is(false));
            assertThat(asyncConnector.isAccepting(),is(false));
            
            {
                // Close an async connection
                HttpTester.Input in = HttpTester.from(async1.getInputStream());
                async1.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\nConnection: close\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("Hello\n"));
            }
            
            // make a new connection and request
            try (Socket blocking3 = new Socket("localhost",blockingConnector.getLocalPort());)
            {
                HttpTester.Input in = HttpTester.from(blocking3.getInputStream());
                blocking3.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("Hello\n"));
            }
        }

        Thread.sleep(500); // TODO avoid lame sleep ???
        assertThat(localConnector.isAccepting(),is(true));
        assertThat(blockingConnector.isAccepting(),is(true));
        assertThat(asyncConnector.isAccepting(),is(true));
        
    }
    
    @Test
    public void testVerifiedStartSequenceAlreadyOpened() throws Exception
    {
        server.setVerifiedStartSequence(true);
        
        try(ServerSocket alreadyOpened = new ServerSocket(0))
        {
            // Add a connector that can be opened
            AtomicInteger opened0 = new AtomicInteger(0);
            ServerConnector connector0 = new ServerConnector(server)
            {
                @Override
                public void open() throws IOException
                {
                    super.open();
                    opened0.set(getLocalPort());
                }
                
            };
            server.addConnector(connector0);
            
            // Add a connector that will fail to open with port in use
            ServerConnector connector1 = new ServerConnector(server);
            connector1.setPort(alreadyOpened.getLocalPort());
            server.addConnector(connector1);
            
            // Add a handler to detect if handlers are started
            AtomicBoolean handlerStarted = new AtomicBoolean(false);
            server.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException
                {                    
                }
                
                @Override
                public void doStart()
                {
                    handlerStarted.set(true);
                }
            });
            
            
            // try to start the server
            try
            {
                server.start();
                Assert.fail();
            }
            catch (BindException e)
            {
                // expected
                assertThat(e.getMessage(),containsString("Address already in use"));
            }
            
            // Check that connector0 was opened OK
            assertThat(opened0.get(),greaterThan(0));
            // and it is now closed
            assertFalse(connector0.isOpen());
            assertFalse(connector0.isRunning());
            
            // Check that handlers were never started
            assertFalse(handlerStarted.get());
            
            // Check that server components were stopped
            assertFalse(server.getBean(QueuedThreadPool.class).isStarted());
        }
    }
    
    @Test
    public void testVerifiedStartSequenceBadHandler() throws Exception
    {
        server.setVerifiedStartSequence(true);

        // Add a connector that can be opened
        AtomicInteger opened = new AtomicInteger(0);
        ServerConnector connector = new ServerConnector(server)
        {
            @Override
            public void open() throws IOException
            {
                super.open();
                opened.set(getLocalPort());
            }

        };
        server.addConnector(connector);


        // Add a handler that will fail to start
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException
            {                    
            }

            @Override
            public void doStart() throws Exception
            {
                throw new Exception("Expected failure during start");
            }
        });


        // try to start the server
        try
        {
            server.start();
            Assert.fail();
        }
        catch (Exception e)
        {
            // expected
            assertThat(e.getMessage(),containsString("Expected failure during start"));
        }

        // Check that connector0 was opened OK
        assertThat(opened.get(),greaterThan(0));
        // and it is now closed
        assertFalse(connector.isOpen());
        assertFalse(connector.isRunning());

        // Check that server components were stopped
        assertFalse(server.getBean(QueuedThreadPool.class).isStarted());
    }


    public static class HelloHandler extends AbstractHandler
    {
        public HelloHandler()
        {
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentType("text/html;charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println("Hello");
        }
        
    }
    
}
