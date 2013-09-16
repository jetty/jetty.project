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
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.junit.Assert;
import org.junit.Test;

public class ReferrerPushStrategyV2Test extends AbstractHTTPSPDYTest
{

    private final String mainResource = "/index.html";
    private final String cssResource = "/style.css";

    @Override
    protected SPDYServerConnector newHTTPSPDYServerConnector(short version)
    {
        SPDYServerConnector connector = super.newHTTPSPDYServerConnector(version);
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version, connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, new ReferrerPushStrategy());
        connector.setDefaultAsyncConnectionFactory(defaultFactory);
        return connector;
    }

    @Test
    public void testPushHeadersAreValid() throws Exception
    {
        InetSocketAddress address = createServer();

        ReferrerPushStrategy pushStrategy = new ReferrerPushStrategy();
        int referrerPushPeriod = 1000;
        pushStrategy.setReferrerPushPeriod(referrerPushPeriod);
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, pushStrategy);
        connector.setDefaultAsyncConnectionFactory(defaultFactory);

        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);
        Session session1 = sendMainRequestAndCSSRequest(address, mainRequestHeaders);

        // Sleep for pushPeriod This should prevent application.js from being mapped as pushResource
        Thread.sleep(referrerPushPeriod + 1);

        sendJSRequest(session1);

        run2ndClientRequests(address, mainRequestHeaders, true);
    }

    @Test
    public void testReferrerPushPeriod() throws Exception
    {
        InetSocketAddress address = createServer();

        ReferrerPushStrategy pushStrategy = new ReferrerPushStrategy();
        int referrerPushPeriod = 1000;
        pushStrategy.setReferrerPushPeriod(referrerPushPeriod);
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, pushStrategy);
        connector.setDefaultAsyncConnectionFactory(defaultFactory);

        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);
        Session session1 = sendMainRequestAndCSSRequest(address, mainRequestHeaders);

        // Sleep for pushPeriod This should prevent application.js from being mapped as pushResource
        Thread.sleep(referrerPushPeriod+1);

        sendJSRequest(session1);

        run2ndClientRequests(address, mainRequestHeaders, false);
    }

    @Test
    public void testMaxAssociatedResources() throws Exception
    {
        InetSocketAddress address = createServer();

        ReferrerPushStrategy pushStrategy = new ReferrerPushStrategy();
        pushStrategy.setMaxAssociatedResources(1);
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, pushStrategy);
        connector.setDefaultAsyncConnectionFactory(defaultFactory);

        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);
        Session session1 = sendMainRequestAndCSSRequest(address, mainRequestHeaders);

        sendJSRequest(session1);

        run2ndClientRequests(address, mainRequestHeaders, false);
    }

    private InetSocketAddress createServer() throws Exception
    {
        return startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                else if (url.endsWith(".js"))
                    output.print("function(){}();");
                baseRequest.setHandled(true);
            }
        });
    }

    private Session sendMainRequestAndCSSRequest(InetSocketAddress address, Headers mainRequestHeaders) throws Exception
    {
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch1 = new CountDownLatch(1);
        Headers associatedRequestHeaders1 = createHeaders(cssResource);
        session1.syn(new SynInfo(associatedRequestHeaders1, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch1.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch1.await(5, TimeUnit.SECONDS));
        return session1;
    }


    private void sendJSRequest(Session session1) throws InterruptedException
    {
        final CountDownLatch associatedResourceLatch2 = new CountDownLatch(1);
        String jsResource = "/application.js";
        Headers associatedRequestHeaders2 = createHeaders(jsResource);
        session1.syn(new SynInfo(associatedRequestHeaders2, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch2.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch2.await(5, TimeUnit.SECONDS));
    }

    private void run2ndClientRequests(InetSocketAddress address, Headers mainRequestHeaders, final boolean validateHeaders) throws Exception
    {
        // Create another client, and perform the same request for the main resource,
        // we expect the css being pushed, but not the js

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        final CountDownLatch pushSynHeadersValid = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                if(validateHeaders)
                    validateHeaders(synInfo.getHeaders(), pushSynHeadersValid);

                Assert.assertTrue(stream.isUnidirectional());
                Assert.assertTrue(synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version())).value().endsWith(".css"));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {

                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue("Main request reply and/or data not received", mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue("Pushed data not received", pushDataLatch.await(5, TimeUnit.SECONDS));
        if(validateHeaders)
            Assert.assertTrue("Push syn headers not valid", pushSynHeadersValid.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = createHeaders(cssResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect the css being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAssociatedResourceWithWrongContentTypeIsNotPushed() throws Exception
    {
        final String fakeResource = "/fake.png";
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                {
                    response.setContentType("text/html");
                    output.print("<html><head/><body>HELLO</body></html>");
                }
                else if (url.equals(fakeResource))
                {
                    response.setContentType("text/html");
                    output.print("<html><head/><body>IMAGE</body></html>");
                }
                else if (url.endsWith(".css"))
                {
                    response.setContentType("text/css");
                    output.print("body { background: #FFF; }");
                }
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        String cssResource = "/stylesheet.css";
        Headers associatedRequestHeaders = createHeaders(cssResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch fakeAssociatedResourceLatch = new CountDownLatch(1);
        Headers fakeAssociatedRequestHeaders = createHeaders(fakeResource);
        session1.syn(new SynInfo(fakeAssociatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    fakeAssociatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(fakeAssociatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource,
        // we expect the css being pushed but not the fake PNG

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                Assert.assertTrue(synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version())).value().endsWith(".css"));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNestedAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                else if (url.endsWith(".gif"))
                    output.print("\u0000");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = createHeaders(cssResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch nestedResourceLatch = new CountDownLatch(1);
        String imageUrl = "/image.gif";
        Headers nestedRequestHeaders = createHeaders(imageUrl, cssResource);

        session1.syn(new SynInfo(nestedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    nestedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(nestedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect the css and the image being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(2);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMainResourceWithReferrerIsNotPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        String associatedResource = "/home.html";
        Headers associatedRequestHeaders = createHeaders(associatedResource);

        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect nothing being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                pushLatch.countDown();
                return null;
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestWithIfModifiedSinceHeaderPreventsPush() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = createHeaders(mainResource);
        mainRequestHeaders.put("If-Modified-Since", "Tue, 27 Mar 2012 16:36:52 GMT");
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = createHeaders(cssResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect the css NOT being pushed as the main request contains an
        // if-modified-since header

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse("We don't expect data to be pushed as the main request contained an if-modified-since header",pushDataLatch.await(1, TimeUnit.SECONDS));
    }

    private void validateHeaders(Headers headers, CountDownLatch pushSynHeadersValid)
    {
        if (validateHeader(headers, HTTPSPDYHeader.STATUS.name(version()), "200")
                && validateHeader(headers, HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1")
                && validateUriHeader(headers))
            pushSynHeadersValid.countDown();
    }

    private boolean validateHeader(Headers headers, String name, String expectedValue)
    {
        Headers.Header header = headers.get(name);
        if (header != null && expectedValue.equals(header.value()))
            return true;
        System.out.println(name + " not valid! " + headers);
        return false;
    }

    private boolean validateUriHeader(Headers headers)
    {
        Headers.Header uriHeader = headers.get(HTTPSPDYHeader.URI.name(version()));
        if (uriHeader != null)
            if (version() == SPDY.V2 && uriHeader.value().startsWith("http://"))
                return true;
            else if (version() == SPDY.V3 && uriHeader.value().startsWith("/")
                    && headers.get(HTTPSPDYHeader.HOST.name(version())) != null && headers.get(HTTPSPDYHeader.SCHEME.name(version())) != null)
                return true;
        System.out.println(HTTPSPDYHeader.URI.name(version()) + " not valid!");
        return false;
    }

    private Headers createHeaders(String resource)
    {
        return createHeaders(resource, mainResource);
    }

    private Headers createHeaders(String resource, String referrer)
    {
        Headers associatedRequestHeaders = createHeadersWithoutReferrer(resource);
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + referrer);
        return associatedRequestHeaders;
    }

    private Headers createHeadersWithoutReferrer(String resource)
    {
        Headers associatedRequestHeaders = new Headers();
        associatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), resource);
        associatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        return associatedRequestHeaders;
    }
}
