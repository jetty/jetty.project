//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;
import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.junit.Test;

public class PushStrategyBenchmarkTest extends AbstractHTTPSPDYTest
{
    // Sample resources size from webtide.com home page
    private final int[] htmlResources = new int[]
            {8 * 1024};
    private final int[] cssResources = new int[]
            {12 * 1024, 2 * 1024};
    private final int[] jsResources = new int[]
            {75 * 1024, 24 * 1024, 36 * 1024};
    private final int[] pngResources = new int[]
            {1024, 45 * 1024, 6 * 1024, 2 * 1024, 2 * 1024, 2 * 1024, 3 * 1024, 512, 512, 19 * 1024, 512, 128, 32};
    private final Set<String> pushedResources = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>();
    private final long roundtrip = 100;
    private final int runs = 10;

    @Test
    public void benchmarkPushStrategy() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new PushStrategyBenchmarkHandler());

        // Plain HTTP
        AsyncConnectionFactory dacf = new ServerHTTPAsyncConnectionFactory(connector);
        connector.setDefaultAsyncConnectionFactory(dacf);
        HttpClient httpClient = new HttpClient();
        // Simulate browsers, that open 6 connection per origin
        httpClient.setMaxConnectionsPerAddress(6);
        httpClient.start();
        benchmarkHTTP(httpClient);
        httpClient.stop();

        // First push strategy
        PushStrategy pushStrategy = new PushStrategy.None();
        dacf = new ServerHTTPSPDYAsyncConnectionFactory(version(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, pushStrategy);
        connector.setDefaultAsyncConnectionFactory(dacf);
        Session session = startClient(version(), address, new ClientSessionFrameListener());
        benchmarkSPDY(pushStrategy, session);
        session.goAway().get(5, TimeUnit.SECONDS);

        // Second push strategy
        pushStrategy = new ReferrerPushStrategy();
        dacf = new ServerHTTPSPDYAsyncConnectionFactory(version(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, pushStrategy);
        connector.setDefaultAsyncConnectionFactory(dacf);
        session = startClient(version(), address, new ClientSessionFrameListener());
        benchmarkSPDY(pushStrategy, session);
        session.goAway().get(5, TimeUnit.SECONDS);
    }

    private void benchmarkHTTP(HttpClient httpClient) throws Exception
    {
        // Warm up
        performHTTPRequests(httpClient);
        performHTTPRequests(httpClient);

        long total = 0;
        for (int i = 0; i < runs; ++i)
        {
            long begin = System.nanoTime();
            int requests = performHTTPRequests(httpClient);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            total += elapsed;
            System.err.printf("HTTP: run %d, %d request(s), roundtrip delay %d ms, elapsed = %d%n",
                    i, requests, roundtrip, elapsed);
        }
        System.err.printf("HTTP: roundtrip delay %d ms, average = %d%n%n",
                roundtrip, total / runs);
    }

    private int performHTTPRequests(HttpClient httpClient) throws Exception
    {
        int result = 0;

        for (int j = 0; j < htmlResources.length; ++j)
        {
            latch.set(new CountDownLatch(cssResources.length + jsResources.length + pngResources.length));

            String primaryPath = "/" + j + ".html";
            String referrer = new StringBuilder("http://localhost:").append(connector.getLocalPort()).append(primaryPath).toString();
            ContentExchange exchange = new ContentExchange(true);
            exchange.setMethod("GET");
            exchange.setRequestURI(primaryPath);
            exchange.setVersion("HTTP/1.1");
            exchange.setAddress(new Address("localhost", connector.getLocalPort()));
            exchange.setRequestHeader("Host", "localhost:" + connector.getLocalPort());
            ++result;
            httpClient.send(exchange);
            Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            Assert.assertEquals(200, exchange.getResponseStatus());

            for (int i = 0; i < cssResources.length; ++i)
            {
                String path = "/" + i + ".css";
                exchange = createExchangeWithReferrer(referrer, path);
                ++result;
                httpClient.send(exchange);
            }
            for (int i = 0; i < jsResources.length; ++i)
            {
                String path = "/" + i + ".js";
                exchange = createExchangeWithReferrer(referrer, path);
                ++result;
                httpClient.send(exchange);
            }
            for (int i = 0; i < pngResources.length; ++i)
            {
                String path = "/" + i + ".png";
                exchange = createExchangeWithReferrer(referrer, path);
                ++result;
                httpClient.send(exchange);
            }

            Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));
        }

        return result;
    }

    private ContentExchange createExchangeWithReferrer(String referrer, String path)
    {
        ContentExchange exchange;
        exchange = new TestExchange();
        exchange.setMethod("GET");
        exchange.setRequestURI(path);
        exchange.setVersion("HTTP/1.1");
        exchange.setAddress(new Address("localhost", connector.getLocalPort()));
        exchange.setRequestHeader("Host", "localhost:" + connector.getLocalPort());
        exchange.setRequestHeader("referer", referrer);
        return exchange;
    }


    private void benchmarkSPDY(PushStrategy pushStrategy, Session session) throws Exception
    {
        // Warm up PushStrategy
        performRequests(session);
        performRequests(session);

        long total = 0;
        for (int i = 0; i < runs; ++i)
        {
            long begin = System.nanoTime();
            int requests = performRequests(session);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            total += elapsed;
            System.err.printf("SPDY(%s): run %d, %d request(s), roundtrip delay %d ms, elapsed = %d%n",
                    pushStrategy.getClass().getSimpleName(), i, requests, roundtrip, elapsed);
        }
        System.err.printf("SPDY(%s): roundtrip delay %d ms, average = %d%n%n",
                pushStrategy.getClass().getSimpleName(), roundtrip, total / runs);
    }

    private int performRequests(Session session) throws Exception
    {
        int result = 0;

        for (int j = 0; j < htmlResources.length; ++j)
        {
            latch.set(new CountDownLatch(cssResources.length + jsResources.length + pngResources.length));
            pushedResources.clear();

            String primaryPath = "/" + j + ".html";
            String referrer = new StringBuilder("http://localhost:").append(connector.getLocalPort()).append(primaryPath).toString();
            Headers headers = new Headers();
            headers.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
            headers.put(HTTPSPDYHeader.URI.name(version()), primaryPath);
            headers.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
            headers.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
            headers.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
            // Wait for the HTML to simulate browser's behavior
            ++result;
            final CountDownLatch htmlLatch = new CountDownLatch(1);
            session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
            {
                @Override
                public void onData(Stream stream, DataInfo dataInfo)
                {
                    dataInfo.consume(dataInfo.length());
                    if (dataInfo.isClose())
                        htmlLatch.countDown();
                }
            });
            Assert.assertTrue(htmlLatch.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < cssResources.length; ++i)
            {
                String path = "/" + i + ".css";
                if (pushedResources.contains(path))
                    continue;
                headers = createRequestHeaders(referrer, path);
                ++result;
                session.syn(new SynInfo(headers, true), new DataListener());
            }
            for (int i = 0; i < jsResources.length; ++i)
            {
                String path = "/" + i + ".js";
                if (pushedResources.contains(path))
                    continue;
                headers = createRequestHeaders(referrer, path);
                ++result;
                session.syn(new SynInfo(headers, true), new DataListener());
            }
            for (int i = 0; i < pngResources.length; ++i)
            {
                String path = "/" + i + ".png";
                if (pushedResources.contains(path))
                    continue;
                headers = createRequestHeaders(referrer, path);
                ++result;
                session.syn(new SynInfo(headers, true), new DataListener());
            }

            Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));
        }

        return result;
    }

    private Headers createRequestHeaders(String referrer, String path)
    {
        Headers headers;
        headers = new Headers();
        headers.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        headers.put(HTTPSPDYHeader.URI.name(version()), path);
        headers.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        headers.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        headers.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        headers.put("referer", referrer);
        return headers;
    }

    private void sleep(long delay) throws ServletException
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(delay);
        }
        catch (InterruptedException x)
        {
            throw new ServletException(x);
        }
    }

    private class PushStrategyBenchmarkHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            // Sleep half of the roundtrip time, to simulate the delay of responses, even for pushed resources
            sleep(roundtrip / 2);
            // If it's not a pushed resource, sleep half of the roundtrip time, to simulate the delay of requests
            if (request.getHeader("x-spdy-push") == null)
                sleep(roundtrip / 2);

            String suffix = target.substring(target.indexOf('.') + 1);
            int index = Integer.parseInt(target.substring(1, target.length() - suffix.length() - 1));

            int contentLength;
            String contentType;
            switch (suffix)
            {
                case "html":
                    contentLength = htmlResources[index];
                    contentType = "text/html";
                    break;
                case "css":
                    contentLength = cssResources[index];
                    contentType = "text/css";
                    break;
                case "js":
                    contentLength = jsResources[index];
                    contentType = "text/javascript";
                    break;
                case "png":
                    contentLength = pngResources[index];
                    contentType = "image/png";
                    break;
                default:
                    throw new ServletException();
            }

            response.setContentType(contentType);
            response.setContentLength(contentLength);
            response.getOutputStream().write(new byte[contentLength]);
        }
    }

    private void addPushedResource(String pushedURI)
    {
        switch (version())
        {
            case SPDY.V2:
            {
                Matcher matcher = Pattern.compile("https?://[^:]+:\\d+(/.*)").matcher(pushedURI);
                Assert.assertTrue(matcher.matches());
                pushedResources.add(matcher.group(1));
                break;
            }
            case SPDY.V3:
            {
                pushedResources.add(pushedURI);
                break;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    private class ClientSessionFrameListener extends SessionFrameListener.Adapter
    {
        @Override
        public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
        {
            String path = synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version())).value();
            addPushedResource(path);
            return new DataListener();
        }
    }

    private class DataListener extends StreamFrameListener.Adapter
    {
        @Override
        public void onData(Stream stream, DataInfo dataInfo)
        {
            dataInfo.consume(dataInfo.length());
            if (dataInfo.isClose())
                latch.get().countDown();
        }
    }

    private class TestExchange extends ContentExchange
    {
        private TestExchange()
        {
            super(true);
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            latch.get().countDown();
        }
    }
}
