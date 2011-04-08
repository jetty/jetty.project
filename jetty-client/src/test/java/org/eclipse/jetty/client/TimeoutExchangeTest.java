// ========================================================================
// Copyright (c) 2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TimeoutExchangeTest
{
    private HttpClient _httpClient;
    private Server _server;
    private int _port;

    @Before
    public void setUp() throws Exception
    {
        startServer();
    }

    @After
    public void tearDown() throws Exception
    {
        stopClient();
        stopServer();
    }

    private void startServer() throws Exception
    {
        _server = new Server();
        _server.setGracefulShutdown(500);
        Connector _connector = new SelectChannelConnector();
        _server.addConnector(_connector);
        Handler handler = new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    Long sleep = Long.parseLong(request.getParameter("sleep"));
                    Thread.sleep(sleep);
                    response.setContentType("text/html");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().println("<h1>Hello</h1>");
                    baseRequest.setHandled(true);
                }
                catch (InterruptedException x)
                {
                    Thread.currentThread().interrupt();
                    throw new ServletException(x);
                }
            }
        };
        _server.setHandler(handler);
        _server.start();
        _port = _connector.getLocalPort();
    }

    private void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
        _server = null;
    }

    private void startClient(long clientTimeout) throws Exception
    {
        _httpClient = new HttpClient();
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(2);
        _httpClient.setTimeout(clientTimeout);
        _httpClient.start();
    }

    private void stopClient() throws Exception
    {
        _httpClient.stop();
//        Thread.sleep(500);
    }

    @Test
    public void testDefaultTimeoutNotExpiring() throws Exception
    {
        startClient(1000);
        long serverSleep = 500;

        CustomContentExchange httpExchange = new CustomContentExchange();
        httpExchange.setURL("http://localhost:" + _port + "/?sleep=" + serverSleep);
        httpExchange.setMethod(HttpMethods.POST);
        httpExchange.setRequestContent(new ByteArrayBuffer("<h1>??</h1>"));
        _httpClient.send(httpExchange);

        Assert.assertTrue(httpExchange.getDoneLatch().await(4 * serverSleep, TimeUnit.MILLISECONDS));
        Assert.assertFalse(httpExchange.isTimeoutOccurred());
        Assert.assertTrue(httpExchange.isResponseReceived());
        Assert.assertFalse(httpExchange.isErrorOccurred());
    }

    @Test
    public void testDefaultTimeoutExpiring() throws Exception
    {
        startClient(500);
        long serverSleep = 1000;

        CustomContentExchange httpExchange = new CustomContentExchange();
        httpExchange.setURL("http://localhost:" + _port + "/?sleep=" + serverSleep);
        httpExchange.setMethod(HttpMethods.POST);
        httpExchange.setRequestContent(new ByteArrayBuffer("<h1>??</h1>"));
        _httpClient.send(httpExchange);

        Assert.assertTrue(httpExchange.getDoneLatch().await(2 * serverSleep, TimeUnit.MILLISECONDS));
        Assert.assertTrue(httpExchange.isTimeoutOccurred());
        Assert.assertFalse(httpExchange.isResponseReceived());
        Assert.assertFalse(httpExchange.isErrorOccurred());
    }

    @Test
    public void testExchangeTimeoutNotExpiring() throws Exception
    {
        startClient(500);
        long serverSleep = 1000;
        long exchangeTimeout = 1500;

        CustomContentExchange httpExchange = new CustomContentExchange();
        httpExchange.setURL("http://localhost:" + _port + "/?sleep=" + serverSleep);
        httpExchange.setMethod(HttpMethods.POST);
        httpExchange.setRequestContent(new ByteArrayBuffer("<h1>??</h1>"));
        httpExchange.setTimeout(exchangeTimeout);
        _httpClient.send(httpExchange);

        Assert.assertTrue(httpExchange.getDoneLatch().await(2 * exchangeTimeout, TimeUnit.MILLISECONDS));
        Assert.assertFalse(httpExchange.isTimeoutOccurred());
        Assert.assertTrue(httpExchange.isResponseReceived());
        Assert.assertFalse(httpExchange.isErrorOccurred());
    }

    @Test
    public void testExchangeTimeoutExpiring() throws Exception
    {
        startClient(5000);
        long serverSleep = 1000;
        long exchangeTimeout = 500;

        CustomContentExchange httpExchange = new CustomContentExchange();
        httpExchange.setURL("http://localhost:" + _port + "/?sleep=" + serverSleep);
        httpExchange.setMethod(HttpMethods.POST);
        httpExchange.setRequestContent(new ByteArrayBuffer("<h1>??</h1>"));
        httpExchange.setTimeout(exchangeTimeout);
        _httpClient.send(httpExchange);

        Assert.assertTrue(httpExchange.getDoneLatch().await(2 * serverSleep, TimeUnit.MILLISECONDS));
        Assert.assertTrue(httpExchange.isTimeoutOccurred());
        Assert.assertFalse(httpExchange.isResponseReceived());
        Assert.assertFalse(httpExchange.isErrorOccurred());
    }

    private class CustomContentExchange extends ContentExchange
    {
        private final CountDownLatch _doneLatch = new CountDownLatch(1);
        private boolean _errorOccurred = false;
        private boolean _timeoutOccurred = false;
        private boolean _responseReceived = false;

        public boolean isErrorOccurred()
        {
            return _errorOccurred;
        }

        public boolean isTimeoutOccurred()
        {
            return _timeoutOccurred;
        }

        public boolean isResponseReceived()
        {
            return _responseReceived;
        }

        public CustomContentExchange()
        {
            super(true);
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            try
            {
                super.onResponseComplete();
            }
            finally
            {
                doTaskCompleted();
            }
        }

        @Override
        protected void onExpire()
        {
            try
            {
                super.onExpire();
            }
            finally
            {
                doTaskCompleted();
            }
        }

        @Override
        protected void onException(Throwable ex)
        {
            try
            {
                super.onException(ex);
            }
            finally
            {
                doTaskCompleted(ex);
            }
        }

        @Override
        protected void onConnectionFailed(Throwable ex)
        {
            try
            {
                super.onConnectionFailed(ex);
            }
            finally
            {
                doTaskCompleted(ex);
            }
        }

        protected void doTaskCompleted()
        {
            int exchangeState = getStatus();

            try
            {
                if (exchangeState == HttpExchange.STATUS_COMPLETED)
                {
                    // process the response as the state is ok
                    try
                    {
                        int responseCode = getResponseStatus();

                        if (responseCode >= HttpStatus.CONTINUE_100 && responseCode < HttpStatus.MULTIPLE_CHOICES_300)
                        {
                            _responseReceived = true;
                        }
                        else
                        {
                            _errorOccurred = true;
                        }
                    }
                    catch (Exception e)
                    {
                        _errorOccurred = true;
                        e.printStackTrace();
                    }
                }
                else if (exchangeState == HttpExchange.STATUS_EXPIRED)
                {
                    _timeoutOccurred = true;
                }
                else
                {
                    _errorOccurred = true;
                }
            }
            finally
            {
                // make sure to lower the latch
                getDoneLatch().countDown();
            }
        }

        protected void doTaskCompleted(Throwable ex)
        {
            try
            {
                _errorOccurred = true;
            }
            finally
            {
                // make sure to lower the latch
                getDoneLatch().countDown();
            }
        }

        public CountDownLatch getDoneLatch()
        {
            return _doneLatch;
        }
    }
}
