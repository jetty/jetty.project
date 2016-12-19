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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class PostServletTest
{
    private static final Logger LOG = Log.getLogger(PostServletTest.class);
    private static final AtomicBoolean posted = new AtomicBoolean(false);
    private static final AtomicReference<Throwable> ex0=new AtomicReference<>();
    private static final AtomicReference<Throwable> ex1=new AtomicReference<>();
    private static CountDownLatch complete;

    public static class BasicReadPostServlet extends HttpServlet
    {
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
        {
            posted.set(true);
            byte[] buffer = new byte[1024];
            try
            {
                int len=request.getInputStream().read(buffer);
                while(len>0)
                {
                    response.getOutputStream().println("read "+len);
                    response.getOutputStream().flush();
                    len = request.getInputStream().read(buffer);
                }
            }
            catch (Exception e0)
            {
                ex0.set(e0);
                try
                {
                    // this read-call should fail immediately
                    request.getInputStream().read(buffer);
                }
                catch (Exception e1)
                {
                    ex1.set(e1);
                    LOG.warn(e1.toString());
                }
            }
            finally
            {
                complete.countDown();
            }
        }
    }

    private Server server;
    private LocalConnector connector;

    @Before
    public void startServer() throws Exception
    {
        complete=new CountDownLatch(1);
        ex0.set(null);
        ex1.set(null);
        posted.set(false);
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(BasicReadPostServlet.class, "/post");

        server.setHandler(context);

        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        this.server.stop();
    }

    @Test
    public void testGoodPost() throws Exception
    {
        StringBuilder req = new StringBuilder();
        req.append("POST /post HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Transfer-Encoding: chunked\r\n");
        req.append("\r\n");
        req.append("6\r\n");
        req.append("Hello ");
        req.append("\r\n");
        req.append("7\r\n");
        req.append("World!\n");
        req.append("\r\n");
        req.append("0\r\n");
        req.append("\r\n");

        String resp = connector.getResponses(req.toString(),1,TimeUnit.SECONDS);

        assertThat("resp", resp, containsString("HTTP/1.1 200 OK"));
        assertThat("resp", resp, containsString("chunked"));
        assertThat("resp", resp, containsString("read 6"));
        assertThat("resp", resp, containsString("read 7"));
        assertThat("resp", resp, containsString("\r\n0\r\n"));

        assertThat(ex0.get(),nullValue());
        assertThat(ex1.get(),nullValue());
    }

    @Test
    public void testBadPost() throws Exception
    {
        StringBuilder req = new StringBuilder(16*1024);
        req.append("POST /post HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Transfer-Encoding: chunked\r\n");
        req.append("\r\n");
        // intentionally bad (not a valid chunked char here)
        for (int i=1024;i-->0;)
            req.append("xxxxxxxxxxxx");
        req.append("\r\n");
        req.append("\r\n");

        try (StacklessLogging scope = new StacklessLogging(ServletHandler.class))
        {
            String resp = connector.getResponses(req.toString());
            assertThat(resp,is("")); // Aborted before response committed
        }
        assertTrue(complete.await(5,TimeUnit.SECONDS));
        assertThat(ex0.get(),not(nullValue()));
        assertThat(ex1.get(),not(nullValue()));
    }

    @Test
    public void testDeferredBadPost() throws Exception
    {
        StringBuilder req = new StringBuilder(16*1024);
        req.append("POST /post HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Transfer-Encoding: chunked\r\n");
        req.append("\r\n");

        try (StacklessLogging scope = new StacklessLogging(ServletHandler.class))
        {
            LocalConnector.LocalEndPoint endp=connector.executeRequest(req.toString());
            Thread.sleep(1000);
            assertFalse(posted.get());

            req.setLength(0);
            // intentionally bad (not a valid chunked char here)
            for (int i=1024;i-->0;)
                req.append("xxxxxxxxxxxx");
            req.append("\r\n");
            req.append("\r\n");

            endp.addInput(req.toString());

            endp.waitUntilClosedOrIdleFor(1,TimeUnit.SECONDS);
            String resp = endp.takeOutputString();

            assertThat("resp", resp, containsString("HTTP/1.1 400 "));

        }

        // null because it was never dispatched!
        assertThat(ex0.get(),nullValue());
        assertThat(ex1.get(),nullValue());
    }


    @Test
    public void testBadSplitPost() throws Exception
    {
        StringBuilder req = new StringBuilder();
        req.append("POST /post HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Connection: close\r\n");
        req.append("Transfer-Encoding: chunked\r\n");
        req.append("\r\n");
        req.append("6\r\n");
        req.append("Hello ");
        req.append("\r\n");

        try (StacklessLogging scope = new StacklessLogging(ServletHandler.class))
        {
            LocalConnector.LocalEndPoint endp=connector.executeRequest(req.toString());
            req.setLength(0);

            while(!posted.get())
                Thread.sleep(100);
            Thread.sleep(100);
            req.append("x\r\n");
            req.append("World\n");
            req.append("\r\n");
            req.append("0\r\n");
            req.append("\r\n");
            endp.addInput(req.toString());

            endp.waitUntilClosedOrIdleFor(1,TimeUnit.SECONDS);
            String resp = endp.takeOutputString();
            assertThat("resp", resp, containsString("HTTP/1.1 200 "));
            assertThat("resp", resp, not(containsString("\r\n0\r\n"))); // aborted
        }
        assertTrue(complete.await(5,TimeUnit.SECONDS));
        assertThat(ex0.get(),not(nullValue()));
        assertThat(ex1.get(),not(nullValue()));
    }

}
