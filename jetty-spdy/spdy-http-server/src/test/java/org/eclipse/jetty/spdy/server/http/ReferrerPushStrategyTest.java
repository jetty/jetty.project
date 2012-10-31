//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.util.Fields;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class ReferrerPushStrategyTest extends AbstractHTTPSPDYTest
{
    private final String mainResource = "/index.html";
    private final int referrerPushPeriod = 1000;
    private final String cssResource = "/style.css";
    private InetSocketAddress serverAddress;
    private ReferrerPushStrategy pushStrategy;
    private ConnectionFactory defaultFactory;
    private Fields mainRequestHeaders;

    public ReferrerPushStrategyTest(short version)
    {
        super(version);
    }

    @Override
    protected HTTPSPDYServerConnector newHTTPSPDYServerConnector(short version)
    {
        return new HTTPSPDYServerConnector(server, version, new HttpConfiguration(), new ReferrerPushStrategy());
    }

    @Before
    public void setUp() throws Exception
    {
        serverAddress = createServer();
        pushStrategy = new ReferrerPushStrategy();
        pushStrategy.setReferrerPushPeriod(referrerPushPeriod);
        defaultFactory = new HTTPSPDYServerConnectionFactory(version, new HttpConfiguration(), pushStrategy);
        connector.addConnectionFactory(defaultFactory);
        if (connector.getConnectionFactory(NPNServerConnectionFactory.class) != null)
            connector.getConnectionFactory(NPNServerConnectionFactory.class).setDefaultProtocol(defaultFactory.getProtocol());
        else
            connector.setDefaultProtocol(defaultFactory.getProtocol());
        mainRequestHeaders = createHeadersWithoutReferrer(mainResource);
    }

    @Test
    public void testPushHeadersAreValid() throws Exception
    {
        sendMainRequestAndCSSRequest();
        run2ndClientRequests(true, true);
    }

    @Test
    public void testUserAgentBlackList() throws Exception
    {
        pushStrategy.setUserAgentBlacklist(Arrays.asList(".*(?i)firefox/16.*"));
        sendMainRequestAndCSSRequest();
        run2ndClientRequests(false, false);
    }

    @Test
    public void testReferrerPushPeriod() throws Exception
    {
        Session session1 = sendMainRequestAndCSSRequest();

        // Sleep for pushPeriod This should prevent application.js from being mapped as pushResource
        Thread.sleep(referrerPushPeriod + 1);
        sendJSRequest(session1);

        run2ndClientRequests(false, true);
    }

    @Test
    public void testMaxAssociatedResources() throws Exception
    {
        pushStrategy.setMaxAssociatedResources(1);
        connector.addConnectionFactory(defaultFactory);
        connector.setDefaultProtocol(defaultFactory.getProtocol()); // TODO I don't think this is right

        Session session1 = sendMainRequestAndCSSRequest();

        sendJSRequest(session1);

        run2ndClientRequests(false, true);
    }

    private InetSocketAddress createServer() throws Exception
    {
        return startHTTPServer(version, new AbstractHandler()
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

    private Session sendMainRequestAndCSSRequest() throws Exception
    {
        Session session1 = startClient(version, serverAddress, null);

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
        Fields associatedRequestHeaders1 = createHeaders(cssResource);
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
        Fields associatedRequestHeaders2 = createHeaders(jsResource);
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

    private void run2ndClientRequests(final boolean validateHeaders,
                                      boolean expectPushResource) throws Exception
    {
        // Create another client, and perform the same request for the main resource,
        // we expect the css being pushed, but not the js

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        final CountDownLatch pushSynHeadersValid = new CountDownLatch(1);
        Session session2 = startClient(version, serverAddress, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                if (validateHeaders)
                    validateHeaders(synInfo.getHeaders(), pushSynHeadersValid);

                assertThat("Stream is unidirectional",stream.isUnidirectional(),is(true));
                assertThat("URI header ends with css", synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version))
                        .value().endsWith
                        ("" +
                        ".css"),
                        is(true));
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
                assertThat("replyInfo.isClose() is false", replyInfo.isClose(), is(false));
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

        assertThat("Main request reply and/or data not received", mainStreamLatch.await(5, TimeUnit.SECONDS), is(true));
        if (expectPushResource)
            assertThat("Pushed data not received", pushDataLatch.await(5, TimeUnit.SECONDS), is(true));
        else
            assertThat("No push data is received", pushDataLatch.await(1, TimeUnit.SECONDS), is(false));
        if (validateHeaders)
            assertThat("Push syn headers not valid", pushSynHeadersValid.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version, new AbstractHandler()
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
        Session session1 = startClient(version, address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Fields mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

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
        Fields associatedRequestHeaders = createHeaders(cssResource);
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
        Session session2 = startClient(version, address, new SessionFrameListener.Adapter()
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
        InetSocketAddress address = startHTTPServer(version, new AbstractHandler()
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
        Session session1 = startClient(version, address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Fields mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

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
        Fields associatedRequestHeaders = createHeaders(cssResource);
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
        Fields fakeAssociatedRequestHeaders = createHeaders(fakeResource);
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
        Session session2 = startClient(version, address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                Assert.assertTrue(synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version)).value().endsWith(".css"));
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
        InetSocketAddress address = startHTTPServer(version, new AbstractHandler()
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
        Session session1 = startClient(version, address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Fields mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

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
        Fields associatedRequestHeaders = createHeaders(cssResource);
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
        Fields nestedRequestHeaders = createHeaders(imageUrl, cssResource);

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
        Session session2 = startClient(version, address, new SessionFrameListener.Adapter()
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
        InetSocketAddress address = startHTTPServer(version, new AbstractHandler()
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
        Session session1 = startClient(version, address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Fields mainRequestHeaders = createHeadersWithoutReferrer(mainResource);

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
        Fields associatedRequestHeaders = createHeaders(associatedResource);

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
        Session session2 = startClient(version, address, new SessionFrameListener.Adapter()
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
        InetSocketAddress address = startHTTPServer(version, new AbstractHandler()
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
        Session session1 = startClient(version, address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Fields mainRequestHeaders = createHeaders(mainResource);
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
        Fields associatedRequestHeaders = createHeaders(cssResource);
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
        Session session2 = startClient(version, address, new SessionFrameListener.Adapter()
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
        Assert.assertFalse("We don't expect data to be pushed as the main request contained an if-modified-since header", pushDataLatch.await(1, TimeUnit.SECONDS));
    }

    private void validateHeaders(Fields headers, CountDownLatch pushSynHeadersValid)
    {
        if (validateHeader(headers, HTTPSPDYHeader.STATUS.name(version), "200")
                && validateHeader(headers, HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1")
                && validateUriHeader(headers))
            pushSynHeadersValid.countDown();
    }

    private boolean validateHeader(Fields headers, String name, String expectedValue)
    {
        Fields.Field header = headers.get(name);
        if (header != null && expectedValue.equals(header.value()))
            return true;
        System.out.println(name + " not valid! " + headers);
        return false;
    }

    private boolean validateUriHeader(Fields headers)
    {
        Fields.Field uriHeader = headers.get(HTTPSPDYHeader.URI.name(version));
        if (uriHeader != null)
            if (version == SPDY.V2 && uriHeader.value().startsWith("http://"))
                return true;
            else if (version == SPDY.V3 && uriHeader.value().startsWith("/")
                    && headers.get(HTTPSPDYHeader.HOST.name(version)) != null && headers.get(HTTPSPDYHeader.SCHEME.name(version)) != null)
                return true;
        System.out.println(HTTPSPDYHeader.URI.name(version) + " not valid!");
        return false;
    }

    private Fields createHeaders(String resource)
    {
        return createHeaders(resource, mainResource);
    }

    private Fields createHeaders(String resource, String referrer)
    {
        Fields associatedRequestHeaders = createHeadersWithoutReferrer(resource);
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + referrer);
        return associatedRequestHeaders;
    }

    private Fields createHeadersWithoutReferrer(String resource)
    {
        Fields requestHeaders = new Fields();
        requestHeaders.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.7; rv:16.0) " +
                "Gecko/20100101 Firefox/16.0");
        requestHeaders.put(HTTPSPDYHeader.METHOD.name(version), "GET");
        requestHeaders.put(HTTPSPDYHeader.URI.name(version), resource);
        requestHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        requestHeaders.put(HTTPSPDYHeader.SCHEME.name(version), "http");
        requestHeaders.put(HTTPSPDYHeader.HOST.name(version), "localhost:" + connector.getLocalPort());
        return requestHeaders;
    }
}
