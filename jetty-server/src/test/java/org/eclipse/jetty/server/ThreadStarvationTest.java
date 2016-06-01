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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class ThreadStarvationTest
{
    final static int BUFFER_SIZE=1024*1024;
    final static int BUFFERS=64;
    final static int CLIENTS=10;
    final static int THREADS=5;
    @Rule
    public TestTracker tracker = new TestTracker();
    private QueuedThreadPool _threadPool;
    private Server _server;
    private ServerConnector _connector;

    private Server prepareServer(Handler handler)
    {
        _threadPool = new QueuedThreadPool();
        _threadPool.setMinThreads(THREADS);
        _threadPool.setMaxThreads(THREADS);
        _threadPool.setDetailedDump(true);
        _server = new Server(_threadPool);
        int acceptors = 1;
        int selectors = 1;
        _connector = new ServerConnector(_server, acceptors, selectors);
        _server.addConnector(_connector);
        _server.setHandler(handler);
        return _server;
    }

    @After
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testReadInput() throws Exception
    {
        prepareServer(new ReadHandler()).start();
        
        try(Socket client = new Socket("localhost", _connector.getLocalPort()))
        {
            client.setSoTimeout(10000);
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            String request = "" +
                    "GET / HTTP/1.0\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "0123456789\r\n";
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();

            String response = IO.toString(is);
            assertEquals(-1, is.read());
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Read Input 10"));
        }
    }

    @Test
    public void testReadStarvation() throws Exception
    {
        prepareServer(new ReadHandler());
        _server.start();

        Socket[] client = new Socket[CLIENTS];
        OutputStream[] os = new OutputStream[client.length];
        InputStream[] is = new InputStream[client.length];

        for (int i = 0; i < client.length; i++)
        {
            client[i] = new Socket("localhost", _connector.getLocalPort());
            client[i].setSoTimeout(10000);

            os[i] = client[i].getOutputStream();
            is[i] = client[i].getInputStream();

            String request = "" +
                    "PUT / HTTP/1.0\r\n" +
                    "host: localhost\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n" +
                    "1";
            os[i].write(request.getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(500);

        for (int i = 0; i < client.length; i++)
        {
            os[i].write(("234567890\r\n").getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(500);

        for (int i = 0; i < client.length; i++)
        {
            String response = IO.toString(is[i]);
            assertEquals(-1, is[i].read());
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Read Input 10"));
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
            while (r < l)
            {
                if (request.getInputStream().read() < 0)
                    break;
                r++;
            }

            response.getOutputStream().write(("Read Input " + r + "\r\n").getBytes());
        }
    }
    

    @Test
    public void testWriteStarvation() throws Exception
    {
        prepareServer(new WriteHandler());
        _server.start();

        Socket[] client = new Socket[CLIENTS];
        OutputStream[] os = new OutputStream[client.length];
        final InputStream[] is = new InputStream[client.length];

        for (int i = 0; i < client.length; i++)
        {
            client[i] = new Socket("localhost", _connector.getLocalPort());
            client[i].setSoTimeout(10000);

            os[i] = client[i].getOutputStream();
            is[i] = client[i].getInputStream();

            String request =
                    "GET / HTTP/1.0\r\n" +
                    "host: localhost\r\n" +
                    "\r\n";
            os[i].write(request.getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(100);

        final AtomicLong total=new AtomicLong();
        final CountDownLatch latch=new CountDownLatch(client.length);
        
        for (int i = client.length; i-->0;)
        {
            final int c=i;
            new Thread()
            {
                @Override
                public void run()
                {
                    byte[] content=new byte[BUFFER_SIZE];
                    int content_length=0;
                    String header= "No HEADER!";
                    try
                    {
                        // Read an initial content buffer
                        int len=0;

                        while (len<BUFFER_SIZE)
                        {
                            int l=is[c].read(content,len,content.length-len);
                            if (l<0)
                                throw new IllegalStateException();
                            len+=l;
                            content_length+=l;
                        }

                        // Look for the end of the header
                        int state=0;
                        loop: for(int j=0;j<len;j++)
                        {
                            content_length--;
                            switch(content[j])
                            {
                                case '\r':
                                    state++;
                                    break;

                                case '\n':
                                    switch(state)
                                    {
                                        case 1: 
                                            state=2;
                                            break;
                                        case 3: 
                                            header=new String(content,0,j,StandardCharsets.ISO_8859_1);
                                            assertThat(header,containsString(" 200 OK"));
                                            break loop;
                                    }
                                    break;

                                default:
                                    state=0;
                                    break;
                            }
                        }

                        // Read the rest of the body
                        while(len>0) 
                        {
                            len=is[c].read(content);
                            if (len>0)
                                content_length+=len;
                        }

                        // System.err.printf("client %d cl=%d %n%s%n",c,content_length,header);
                        total.addAndGet(content_length);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                    finally
                    {
                        latch.countDown();
                    }
                }
            }.start();

        }

        latch.await();
        assertEquals(CLIENTS*BUFFERS*BUFFER_SIZE,total.get());
    }
    

    protected static class WriteHandler extends AbstractHandler
    {
        byte[] content=new byte[BUFFER_SIZE];
        {
            Arrays.fill(content,(byte)'x');
        }
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            OutputStream out = response.getOutputStream();
            for (int i=0;i<BUFFERS;i++)
            {
                out.write(content);
                out.flush();
            }
        }
    }

    
}
