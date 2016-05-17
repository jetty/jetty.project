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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class GracefulStopTest 
{
    /**
     * Test of standard graceful timeout mechanism when a block request does
     * not complete
     * @throws Exception on test failure
     */
    @Test
    public void testGracefulNoWaiter() throws Exception
    {
        Server server= new Server();
        server.setStopTimeout(1000);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        final int port=connector.getLocalPort();
        Socket client = new Socket("127.0.0.1", port);
        client.getOutputStream().write((
                "POST / HTTP/1.0\r\n"+
                        "Host: localhost:"+port+"\r\n" +
                        "Content-Type: plain/text\r\n" +
                        "Content-Length: 10\r\n" +
                        "\r\n"+
                        "12345"
                ).getBytes());
        client.getOutputStream().flush();
        handler.latch.await();

        long start = System.nanoTime();
        server.stop();
        long stop = System.nanoTime();
        
        // No Graceful waiters
        assertThat(TimeUnit.NANOSECONDS.toMillis(stop-start),lessThan(900L));

        assertThat(client.getInputStream().read(),Matchers.is(-1));

        assertThat(handler.handling.get(),Matchers.is(false));
        assertThat(handler.thrown.get(),
                Matchers.anyOf(
                instanceOf(ClosedChannelException.class),
                instanceOf(EofException.class),
                instanceOf(EOFException.class))
                );

        client.close();
    }

    /**
     * Test of standard graceful timeout mechanism when a block request does
     * not complete
     * @throws Exception on test failure
     */
    @Test
    public void testGracefulTimeout() throws Exception
    {
        Server server= new Server();
        server.setStopTimeout(1000);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        TestHandler handler = new TestHandler();
        StatisticsHandler stats = new StatisticsHandler();
        server.setHandler(stats);
        stats.setHandler(handler);

        server.start();
        final int port=connector.getLocalPort();
        Socket client = new Socket("127.0.0.1", port);
        client.getOutputStream().write((
                "POST / HTTP/1.0\r\n"+
                        "Host: localhost:"+port+"\r\n" +
                        "Content-Type: plain/text\r\n" +
                        "Content-Length: 10\r\n" +
                        "\r\n"+
                        "12345"
                ).getBytes());
        client.getOutputStream().flush();
        handler.latch.await();

        long start = System.nanoTime();
        try
        {
            server.stop();
            Assert.fail();
        }
        catch(TimeoutException e)
        {
            long stop = System.nanoTime();
            // No Graceful waiters
            assertThat(TimeUnit.NANOSECONDS.toMillis(stop-start),greaterThan(900L));
        }

        assertThat(client.getInputStream().read(),Matchers.is(-1));

        assertThat(handler.handling.get(),Matchers.is(false));
        assertThat(handler.thrown.get(),instanceOf(ClosedChannelException.class));

        client.close();
    }


    /**
     * Test of standard graceful timeout mechanism when a block request does
     * complete. Note that even though the request completes after 100ms, the
     * stop always takes 1000ms
     * @throws Exception on test failure
     */
    @Test
    public void testGracefulComplete() throws Exception
    {
        assumeTrue(!OS.IS_WINDOWS);
        Server server= new Server();
        server.setStopTimeout(10000);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        TestHandler handler = new TestHandler();
        StatisticsHandler stats = new StatisticsHandler();
        server.setHandler(stats);
        stats.setHandler(handler);

        server.start();
        final int port=connector.getLocalPort();

        try(final Socket client1 = new Socket("127.0.0.1", port);final Socket client2 = new Socket("127.0.0.1", port);)
        {
            client1.getOutputStream().write((
                    "POST / HTTP/1.0\r\n"+
                            "Host: localhost:"+port+"\r\n" +
                            "Content-Type: plain/text\r\n" +
                            "Content-Length: 10\r\n" +
                            "\r\n"+
                            "12345"
                    ).getBytes());
            client1.getOutputStream().flush();
            handler.latch.await();

            new Thread()
            {
                @Override
                public void run() 
                {
                    long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                    long end = now+500;
                    

                    
                    try
                    {
                        Thread.sleep(100);

                        // Try creating a new connection
                        try
                        {
                            new Socket("127.0.0.1", port);
                            throw new IllegalStateException();
                        }
                        catch(ConnectException e)
                        {
                            
                        }
                        
                        // Try another request on existing connection

                        client2.getOutputStream().write((
                                "GET / HTTP/1.0\r\n"+
                                        "Host: localhost:"+port+"\r\n" +
                                        "\r\n"
                                ).getBytes());
                        client2.getOutputStream().flush();
                        String response2 = IO.toString(client2.getInputStream());
                        assertThat(response2, containsString(" 503 "));

                        now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                        Thread.sleep(Math.max(1,end-now));
                        client1.getOutputStream().write("567890".getBytes());
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }.start();

            long start = System.nanoTime();
            server.stop();
            long stop = System.nanoTime();
            assertThat(TimeUnit.NANOSECONDS.toMillis(stop-start),greaterThan(490L));
            assertThat(TimeUnit.NANOSECONDS.toMillis(stop-start),lessThan(10000L));

            String response = IO.toString(client1.getInputStream());

            assertThat(handler.handling.get(),Matchers.is(false));
            assertThat(response, containsString(" 200 OK"));
            assertThat(response, containsString("read 10/10"));
            
            assertThat(stats.getRequests(),Matchers.is(2));
            assertThat(stats.getResponses5xx(),Matchers.is(1));
        }
    }


    static class TestHandler extends AbstractHandler 
    {		
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        final AtomicBoolean handling = new AtomicBoolean(false);

        @Override
        public void handle(String target, Request baseRequest,
                HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException 
        {
            handling.set(true);
            latch.countDown();
            int c=0;
            try
            {
                int content_length = request.getContentLength();
                InputStream in = request.getInputStream();

                while(true)
                {
                    if (in.read()<0)
                        break;
                    c++;
                }

                baseRequest.setHandled(true);
                response.setStatus(200);
                response.getWriter().printf("read %d/%d%n",c,content_length);
            }
            catch(Throwable th)
            {
                thrown.set(th);
            }
            finally
            {
                handling.set(false);
            }
        }
    }

}
