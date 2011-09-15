/*
 * Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.jetty.client;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.SocketTimeoutException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractHttpExchangeCancelTest
{
    private Server server;
    private Connector connector;

    @Before
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector();
        server.addConnector(connector);
        server.setHandler(new EmptyHandler());
        server.start();
    }

    @After
    public void tearDown() throws Exception
    {
        server.stop();
        server.join();
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnSend1() throws Exception
    {
        // One of the first things that HttpClient.send() does
        // is to change the status of the exchange
        // We exploit that to be sure the exchange is canceled
        // without race conditions
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            void setStatus(int status)
            {
                // Cancel before setting the new status
                if (getStatus() == HttpExchange.STATUS_START &&
                    status == STATUS_WAITING_FOR_CONNECTION)
                    cancel();
                super.setStatus(status);
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);
        // Cancelling here is wrong and makes the test fail spuriously
        // due to a race condition with send(): the send() can complete
        // before the exchange is canceled so it will be in STATUS_COMPLETE
        // which will fail the test.
//        exchange.cancel();

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnSend2() throws Exception
    {
        // One of the first things that HttpClient.send() does
        // is to change the status of the exchange
        // We exploit that to be sure the exchange is canceled
        // without race conditions
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            void setStatus(int status)
            {
                // Cancel after setting the new status
                int oldStatus = getStatus();
                super.setStatus(status);
                if (oldStatus == STATUS_START &&
                    getStatus() == HttpExchange.STATUS_WAITING_FOR_CONNECTION)
                    cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);
        // Cancelling here is wrong and makes the test fail spuriously
        // due to a race condition with send(): the send() can complete
        // before the exchange is canceled so it will be in STATUS_COMPLETE
        // which will fail the test.
//        exchange.cancel();

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnRequestCommitted() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onRequestCommitted() throws IOException
            {
                super.onRequestCommitted();
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnRequestComplete() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onRequestComplete() throws IOException
            {
                super.onRequestComplete();
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnResponseStatus() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
            {
                super.onResponseStatus(version, status, reason);
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnResponseHeader() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onResponseHeader(Buffer name, Buffer value) throws IOException
            {
                super.onResponseHeader(name, value);
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnResponseHeadersComplete() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onResponseHeaderComplete() throws IOException
            {
                super.onResponseHeaderComplete();
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnResponseContent() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onResponseContent(Buffer content) throws IOException
            {
                super.onResponseContent(content);
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/?action=body");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_CANCELLED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeCancelOnResponseComplete() throws Exception
    {
        TestHttpExchange exchange = new TestHttpExchange()
        {
            @Override
            protected void onResponseComplete() throws IOException
            {
                super.onResponseComplete();
                cancel();
            }
        };
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/");

        getHttpClient().send(exchange);

        int status = exchange.waitForDone();
        assertTrue(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
        assertEquals(HttpExchange.STATUS_COMPLETED, status);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeOnServerException() throws Exception
    {
        try
        {
            ((StdErrLog)Log.getLogger(HttpConnection.class)).setHideStacks(true);
            TestHttpExchange exchange = new TestHttpExchange();
            exchange.setAddress(newAddress());
            exchange.setRequestURI("/?action=throw");

            getHttpClient().send(exchange);

            int status = exchange.waitForDone();
            assertEquals(HttpExchange.STATUS_COMPLETED, status);
            assertTrue(exchange.isResponseCompleted());
            assertFalse(exchange.isFailed());
            assertFalse(exchange.isAssociated());
        }
        finally
        {
            ((StdErrLog)Log.getLogger(HttpConnection.class)).setHideStacks(false);
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHttpExchangeOnExpire() throws Exception
    {
        HttpClient httpClient = getHttpClient();
        httpClient.stop();
        httpClient.setTimeout(1000);
        httpClient.start();

        TestHttpExchange exchange = new TestHttpExchange();
        exchange.setAddress(newAddress());
        exchange.setRequestURI("/?action=wait5000");

        long start = System.currentTimeMillis();
        httpClient.send(exchange);

        int status = exchange.waitForDone();
        long end = System.currentTimeMillis();
        
        assertTrue(HttpExchange.STATUS_EXPIRED==status||HttpExchange.STATUS_EXCEPTED==status);
        assertFalse(exchange.isResponseCompleted());
        assertTrue(end-start<4000);
        assertTrue(exchange.isExpired());
        assertFalse(exchange.isFailed());
        assertFalse(exchange.isAssociated());
    }

    /* ------------------------------------------------------------ */
    protected abstract HttpClient getHttpClient();

    /* ------------------------------------------------------------ */
    protected Address newAddress()
    {
        return new Address("localhost", connector.getLocalPort());
    }

    /* ------------------------------------------------------------ */
    private static class EmptyHandler extends AbstractHandler
    {
        /* ------------------------------------------------------------ */
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);
            String action = httpRequest.getParameter("action");
            if (action != null)
            {
                if ("body".equals(action))
                {
                    ServletOutputStream output = httpResponse.getOutputStream();
                    output.write("body".getBytes("UTF-8"));
//                    output.flush();
                }
                else if ("throw".equals(action))
                {
                    throw new ServletException();
                }
                else if (action.startsWith("wait"))
                {
                    long sleep = Long.valueOf(action.substring("wait".length()));
                    long start=System.currentTimeMillis();
                    try
                    {
                        Thread.sleep(sleep);
                        long end=System.currentTimeMillis();
                        assertTrue("Duration "+(end-start)+" >~ "+sleep,(end-start)>sleep-100);
                    }
                    catch (InterruptedException x)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected static class TestHttpExchange extends ContentExchange
    {
        private boolean responseCompleted;
        private boolean failed = false;
        private boolean expired = false;

        /* ------------------------------------------------------------ */
        protected TestHttpExchange()
        {
            super(true);
        }

        /* ------------------------------------------------------------ */
        @Override
        protected synchronized void onResponseComplete() throws IOException
        {
            this.responseCompleted = true;
        }

        /* ------------------------------------------------------------ */
        public synchronized boolean isResponseCompleted()
        {
            return responseCompleted;
        }

        /* ------------------------------------------------------------ */
        @Override
        protected synchronized void onException(Throwable ex)
        {
            if (ex instanceof SocketTimeoutException ||
                ex.getCause() instanceof SocketTimeoutException)
                expired=true;
            else
                failed = true;
        }

        /* ------------------------------------------------------------ */
        public synchronized boolean isFailed()
        {
            return failed;
        }

        /* ------------------------------------------------------------ */
        @Override
        protected synchronized void onExpire()
        {
            this.expired = true;
        }

        /* ------------------------------------------------------------ */
        public synchronized boolean isExpired()
        {
            return expired;
        }
    }
}
