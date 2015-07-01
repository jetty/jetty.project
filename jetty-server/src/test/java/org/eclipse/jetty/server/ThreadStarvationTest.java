//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class ThreadStarvationTest extends HttpServerTestFixture
{
    ServerConnector _connector;
    
    @Rule
    public TestTracker tracker = new TestTracker();

    @Before
    public void init() throws Exception
    {
        _threadPool.setMinThreads(4);
        _threadPool.setMaxThreads(4);
        _threadPool.setDetailedDump(false);
        _connector = new ServerConnector(_server,1,1);
        _connector.setIdleTimeout(10000);
    }

    @Test
    public void testReadInput() throws Exception
    {
        startServer(_connector,new ReadHandler());
        System.err.println(_threadPool.dump());
        
        Socket client=newSocket(_serverURI.getHost(),_serverURI.getPort());
        client.setSoTimeout(10000);

        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        os.write((
                "GET / HTTP/1.0\r\n"+
                "host: "+_serverURI.getHost()+":"+_serverURI.getPort()+"\r\n"+
                "content-length: 10\r\n" +
                "\r\n" +
                "0123456789\r\n").getBytes("utf-8"));
        os.flush();

        String response = IO.toString(is);
        assertEquals(-1, is.read());
        assertThat(response,containsString("200 OK"));
        assertThat(response,containsString("Read Input 10"));

    }
    
    @Test
    public void testEWYKStarvation() throws Exception
    {
        System.setProperty("org.eclipse.jetty.io.ManagedSelector$SelectorProducer.ExecutionStrategy","org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume");
        startServer(_connector,new ReadHandler());
        
        Socket[] client = new Socket[3];
        OutputStream[] os = new OutputStream[client.length];
        InputStream[] is = new InputStream[client.length];
        
        for (int i=0;i<client.length;i++)
        {
            client[i]=newSocket(_serverURI.getHost(),_serverURI.getPort());
            client[i].setSoTimeout(10000);

            os[i]=client[i].getOutputStream();
            is[i]=client[i].getInputStream();

            os[i].write((
                    "PUT / HTTP/1.0\r\n"+
                            "host: "+_serverURI.getHost()+":"+_serverURI.getPort()+"\r\n"+
                            "content-length: 10\r\n" +
                    "\r\n1").getBytes("utf-8"));
            os[i].flush();
        }
        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (int i=0;i<client.length;i++)
        {
            os[i].write(("234567890\r\n").getBytes("utf-8"));
            os[i].flush();
        }

        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (int i=0;i<client.length;i++)
        {
            String response = IO.toString(is[i]);
            assertEquals(-1, is[i].read());
            assertThat(response,containsString("200 OK"));
            assertThat(response,containsString("Read Input 10"));
        }
        
    }
    

    @Test
    public void testPECStarvation() throws Exception
    {
        System.setProperty("org.eclipse.jetty.io.ManagedSelector$SelectorProducer.ExecutionStrategy","org.eclipse.jetty.util.thread.strategy.ProduceExecuteConsume");

        startServer(_connector,new ReadHandler());
        System.err.println(_threadPool.dump());
        
        Socket[] client = new Socket[3];
        OutputStream[] os = new OutputStream[client.length];
        InputStream[] is = new InputStream[client.length];
        
        for (int i=0;i<client.length;i++)
        {
            client[i]=newSocket(_serverURI.getHost(),_serverURI.getPort());
            client[i].setSoTimeout(10000);

            os[i]=client[i].getOutputStream();
            is[i]=client[i].getInputStream();

            os[i].write((
                    "PUT / HTTP/1.0\r\n"+
                            "host: "+_serverURI.getHost()+":"+_serverURI.getPort()+"\r\n"+
                            "content-length: 10\r\n" +
                    "\r\n1").getBytes("utf-8"));
            os[i].flush();
        }
        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (int i=0;i<client.length;i++)
        {
            os[i].write(("234567890\r\n").getBytes("utf-8"));
            os[i].flush();
        }

        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (int i=0;i<client.length;i++)
        {
            String response = IO.toString(is[i]);
            assertEquals(-1, is[i].read());
            assertThat(response,containsString("200 OK"));
            assertThat(response,containsString("Read Input 10"));
        }
        
    }
    

    protected static class ReadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            int l = request.getContentLength();
            int r = 0;
            while (r<l)
            {
                if (request.getInputStream().read()>=0)
                    r++;
            }
            
            response.getOutputStream().write(("Read Input "+r+"\r\n").getBytes());
        }
    }
}
