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

package org.eclipse.jetty.spdy.server.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlets.gzip.GzipHandler;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

public class ReferrerPushStrategyTest extends AbstractHTTPSPDYTest
{
    private static final Logger LOG = Log.getLogger(ReferrerPushStrategyTest.class);

    private final int referrerPushPeriod = 1000;
    private final String mainResource = "/index.html";
    private final String cssResource = "/style.css";
    private InetSocketAddress serverAddress;
    private ReferrerPushStrategy pushStrategy;
    private ConnectionFactory defaultFactory;
    private Fields mainRequestHeaders;
    private Fields associatedCSSRequestHeaders;
    private Fields associatedJSRequestHeaders;

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
        associatedCSSRequestHeaders = createHeaders(cssResource);
        associatedJSRequestHeaders = createHeaders("/application.js");
    }

    @Test
    public void testPushHeadersAreValid() throws Exception
    {
        sendMainRequestAndCSSRequest(null, false);
        run2ndClientRequests(true, true);
    }

    @Test
    public void testClientResetsPushStreams() throws Exception
    {
        ((StdErrLog)Log.getLogger("org.eclipse.jetty.server.HttpChannel")).setHideStacks(true);
        sendMainRequestAndCSSRequest(null, false);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        final CountDownLatch pushSynHeadersValid = new CountDownLatch(1);
        Session session = startClient(version, serverAddress, null);
        // Send main request. That should initiate the push push's which get reset by the client
        sendRequest(session, mainRequestHeaders, pushSynHeadersValid, pushDataLatch, true);

        assertThat("No push data is received", pushDataLatch.await(1, TimeUnit.SECONDS), is(false));
        assertThat("Push push headers valid", pushSynHeadersValid.await(5, TimeUnit.SECONDS), is(true));

        sendRequest(session, associatedCSSRequestHeaders, pushSynHeadersValid, pushDataLatch, true);
        ((StdErrLog)Log.getLogger("org.eclipse.jetty.server.HttpChannel")).setHideStacks(false);
    }

    @Test
    public void testUserAgentBlackList() throws Exception
    {
        pushStrategy.setUserAgentBlacklist(Arrays.asList(".*(?i)firefox/16.*"));
        sendMainRequestAndCSSRequest(null, false);
        run2ndClientRequests(false, false);
    }

    @Test
    public void testReferrerPushPeriod() throws Exception
    {
        Session session = sendMainRequestAndCSSRequest(null, false);

        // Sleep for pushPeriod This should prevent application.js from being mapped as pushResource
        Thread.sleep(referrerPushPeriod + 1);
        sendRequest(session, associatedJSRequestHeaders, null, null, false);

        run2ndClientRequests(false, true);
    }

    @Test
    public void testMaxAssociatedResources() throws Exception
    {
        pushStrategy.setMaxAssociatedResources(1);
        Session session = sendMainRequestAndCSSRequest(null, false);
        sendRequest(session, associatedJSRequestHeaders, null, null, false);

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(2);
        final CountDownLatch pushSynHeadersValid = new CountDownLatch(1);
        final CountDownLatch pushResponseHeaders = new CountDownLatch(1);
        Session session2 = startClient(version, serverAddress, null);
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                validateHeaders(pushInfo.getHeaders(), pushSynHeadersValid);

                assertThat("Stream is unidirectional", stream.isUnidirectional(), is(true));
                assertThat("URI header ends with css", pushInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version))
                        .value().endsWith
                                ("" +
                                        ".css"),
                        is(true));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersInfo headersInfo)
                    {
                        Fields headers = headersInfo.getHeaders();
                        if (validateHeader(headers, HTTPSPDYHeader.STATUS.name(version), "200 OK")
                                && validateHeader(headers, HTTPSPDYHeader.VERSION.name(version),
                                "HTTP/1.1") && validateHeader(headers, "content-encoding", "gzip"))
                            pushResponseHeaders.countDown();
                    }

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }

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

        assertThat("Main request reply and/or data received", mainStreamLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("Not more than one push is received", pushDataLatch.await(1, TimeUnit.SECONDS), is(false));
        assertThat("Push push headers valid", pushSynHeadersValid.await(5, TimeUnit.SECONDS), is(true));
        assertThat("Push response headers are valid", pushResponseHeaders.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testMaxConcurrentStreamsToDisablePush() throws Exception
    {
        final CountDownLatch pushReceivedLatch = new CountDownLatch(1);

        Session pushCacheBuildSession = startClient(version, serverAddress, null);

        pushCacheBuildSession.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter());
        pushCacheBuildSession.syn(new SynInfo(associatedCSSRequestHeaders, true), new StreamFrameListener.Adapter());

        Session session = startClient(version, serverAddress, null);

        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.MAX_CONCURRENT_STREAMS, 0));
        SettingsInfo settingsInfo = new SettingsInfo(settings);
        session.settings(settingsInfo);

        ((StdErrLog)Log.getLogger("org.eclipse.jetty.spdy.server.http" +
                        ".HttpTransportOverSPDY$PushResourceCoordinator$1")).setHideStacks(true);
        session.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                pushReceivedLatch.countDown();
                return super.onPush(stream, pushInfo);
            }
        });

        assertThat("No push stream is received", pushReceivedLatch.await(1, TimeUnit.SECONDS), is(false));
        ((StdErrLog)Log.getLogger("org.eclipse.jetty.spdy.server.http" +
                                ".HttpTransportOverSPDY$PushResourceCoordinator$1")).setHideStacks(false);
    }

    @Test
    public void testPushResourceOrder() throws Exception
    {
        final CountDownLatch allExpectedPushesReceivedLatch = new CountDownLatch(4);
        final CountDownLatch allPushDataReceivedLatch = new CountDownLatch(4);

        Session pushCacheBuildSession = startClient(version, serverAddress, null);

        sendRequest(pushCacheBuildSession, mainRequestHeaders, null, null, false);
        sendRequest(pushCacheBuildSession, associatedCSSRequestHeaders, null, null, false);
        sendRequest(pushCacheBuildSession, associatedJSRequestHeaders, null, null, false);
        sendRequest(pushCacheBuildSession, createHeaders("/image1.jpg", mainResource), null, null, false);
        sendRequest(pushCacheBuildSession, createHeaders("/image2.jpg", mainResource), null, null, false);

        Session session = startClient(version, serverAddress, null);

        session.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                LOG.info("onPush: stream: {}, pushInfo: {}", stream, pushInfo);
                String uriHeader = pushInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version)).value();
                switch ((int)allExpectedPushesReceivedLatch.getCount())
                {
                    case 4:
                        assertThat("1st pushed resource is the css", uriHeader.endsWith("css"), is(true));
                        break;
                    case 3:
                        assertThat("2nd pushed resource is the js", uriHeader.endsWith("js"), is(true));
                        break;
                    case 2:
                        assertThat("3rd pushed resource is image1", uriHeader.endsWith("image1.jpg"),
                                is(true));
                        break;
                    case 1:
                        assertThat("4th pushed resource is image2", uriHeader.endsWith("image2.jpg"),
                                is(true));
                        break;
                }
                allExpectedPushesReceivedLatch.countDown();
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        if(dataInfo.isClose())
                            allPushDataReceivedLatch.countDown();
                    }
                };
            }
        });

        assertThat("All expected push resources have been received", allExpectedPushesReceivedLatch.await(5,
                TimeUnit.SECONDS), is(true));
        assertThat("All push data has been fully received", allPushDataReceivedLatch.await(5, TimeUnit.SECONDS),
                is(true));
    }

    @Test
    public void testThatPushResourcesAreUnique() throws Exception
    {
        final CountDownLatch pushReceivedLatch = new CountDownLatch(2);
        sendMainRequestAndCSSRequest(null, false);
        sendMainRequestAndCSSRequest(null, false);

        Session session = startClient(version, serverAddress, null);

        session.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                pushReceivedLatch.countDown();
                LOG.info("Push received: {}", pushInfo);
                return null;
            }
        });

        assertThat("style.css has been pushed only once", pushReceivedLatch.await(1, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testPushResourceAreSentNonInterleaved() throws Exception
    {
        final CountDownLatch allExpectedPushesReceivedLatch = new CountDownLatch(4);
        final CountDownLatch allPushDataReceivedLatch = new CountDownLatch(4);
        final CopyOnWriteArrayList<Integer> dataReceivedOrder = new CopyOnWriteArrayList<>();

        InetSocketAddress bigResponseServerAddress = startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                byte[] bytes = new byte[32768];
                new Random().nextBytes(bytes);
                ServletOutputStream outputStream = response.getOutputStream();
                outputStream.write(bytes);
                baseRequest.setHandled(true);
            }
        });
        Session pushCacheBuildSession = startClient(version, bigResponseServerAddress, null);

        Fields mainResourceHeaders = createHeadersWithoutReferrer(mainResource);
        sendRequest(pushCacheBuildSession, mainResourceHeaders, null, null, false);
        sendRequest(pushCacheBuildSession, createHeaders("/style.css", mainResource), null, null, false);
        sendRequest(pushCacheBuildSession, createHeaders("/javascript.js", mainResource), null, null, false);
        sendRequest(pushCacheBuildSession, createHeaders("/image1.jpg", mainResource), null, null, false);
        sendRequest(pushCacheBuildSession, createHeaders("/image2.jpg", mainResource), null, null, false);

        Session session = startClient(version, bigResponseServerAddress, null);

        session.syn(new SynInfo(mainResourceHeaders, true), new StreamFrameListener.Adapter()
        {
            AtomicInteger currentStreamId = new AtomicInteger(2);

            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                LOG.info("Received push for stream: {} {}", stream.getId(), pushInfo);
                String uriHeader = pushInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version)).value();
                switch ((int)allExpectedPushesReceivedLatch.getCount())
                {
                    case 4:
                        assertThat("1st pushed resource is the css", uriHeader.endsWith("css"), is(true));
                        break;
                    case 3:
                        assertThat("2nd pushed resource is the js", uriHeader.endsWith("js"), is(true));
                        break;
                    case 2:
                        assertThat("3rd pushed resource is image1", uriHeader.endsWith("image1.jpg"),
                                is(true));
                        break;
                    case 1:
                        assertThat("4th pushed resource is image2", uriHeader.endsWith("image2.jpg"),
                                is(true));
                        break;
                }
                allExpectedPushesReceivedLatch.countDown();
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        if (stream.getId() != currentStreamId.get())
                            throw new IllegalStateException("Streams interleaved. Expected StreamId: " +
                                    currentStreamId + " but was: " + stream.getId());
                        dataInfo.consume(dataInfo.available());
                        if (dataInfo.isClose())
                        {
                            currentStreamId.compareAndSet(currentStreamId.get(), currentStreamId.get() + 2);
                            dataReceivedOrder.add(stream.getId());
                            allPushDataReceivedLatch.countDown();
                        }
                        LOG.info(stream.getId() + ":" + dataInfo);
                    }
                };
            }
        });

        assertThat("All push resources received", allExpectedPushesReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("All pushData received", allPushDataReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("The data for different push streams has not been interleaved",
                dataReceivedOrder.toString(), equalTo("[2, 4, 6, 8]"));
        LOG.info(dataReceivedOrder.toString());
    }

    private InetSocketAddress createServer() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(new AbstractHandler()
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
        return startHTTPServer(version, gzipHandler);
    }

    private Session sendMainRequestAndCSSRequest(SessionFrameListener sessionFrameListener, boolean awaitPush) throws Exception
    {
        Session session = startClient(version, serverAddress, sessionFrameListener);

        CountDownLatch pushDataLatch = new CountDownLatch(2);
        sendRequest(session, mainRequestHeaders, null, pushDataLatch, false);
        sendRequest(session, associatedCSSRequestHeaders, null, pushDataLatch, false);
        if (awaitPush)
            assertThat("pushes have been received", pushDataLatch.await(5, TimeUnit.SECONDS), is(true));

        return session;
    }

    private void sendRequest(Session session, Fields requestHeaders, final CountDownLatch pushSynHeadersValid,
                             final CountDownLatch pushDataLatch, final boolean resetPush) throws InterruptedException
    {
        LOG.info("sendRequest. headers={},resetPush={}", requestHeaders, resetPush);
        final CountDownLatch dataReceivedLatch = new CountDownLatch(1);
        session.syn(new SynInfo(requestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                if (pushSynHeadersValid != null)
                    validateHeaders(pushInfo.getHeaders(), pushSynHeadersValid);

                assertThat("Stream is unidirectional", stream.isUnidirectional(), is(true));
                assertThat("URI header ends with css", pushInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version))
                        .value().endsWith
                                ("" +
                                        ".css"),
                        is(true));
                if (resetPush)
                    stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new Callback.Adapter());
                return new StreamFrameListener.Adapter()
                {

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        pushDataLatch.countDown();
                    }
                };
            }

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertThat(replyInfo.getHeaders().get(HTTPSPDYHeader.STATUS.name(version)).value(), is("200 OK"));
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    dataReceivedLatch.countDown();
            }
        }, new Promise.Adapter<Stream>());
        assertThat(dataReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private void run2ndClientRequests(final boolean validateHeaders,
                                      boolean expectPushResource) throws Exception
    {
        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        final CountDownLatch pushSynHeadersValid = new CountDownLatch(1);
        final CountDownLatch pushResponseHeaders = new CountDownLatch(1);
        Session session2 = startClient(version, serverAddress, null);
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                if (validateHeaders)
                    validateHeaders(pushInfo.getHeaders(), pushSynHeadersValid);

                assertThat("Stream is unidirectional", stream.isUnidirectional(), is(true));
                assertThat("URI header ends with css", pushInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version))
                        .value().endsWith
                                ("" +
                                        ".css"),
                        is(true));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersInfo headersInfo)
                    {
                        Fields headers = headersInfo.getHeaders();
                        if (validateHeader(headers, HTTPSPDYHeader.STATUS.name(version), "200 OK")
                                && validateHeader(headers, HTTPSPDYHeader.VERSION.name(version),
                                "HTTP/1.1") && validateHeader(headers, "content-encoding", "gzip"))
                            pushResponseHeaders.countDown();
                    }

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }

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

        assertThat("Main request reply and/or data received", mainStreamLatch.await(5, TimeUnit.SECONDS), is(true));
        if (expectPushResource)
            assertThat("Pushed data received", pushDataLatch.await(5, TimeUnit.SECONDS), is(true));
        else
            assertThat("No push data is received", pushDataLatch.await(1, TimeUnit.SECONDS), is(false));
        if (validateHeaders)
        {
            assertThat("Push push headers valid", pushSynHeadersValid.await(5, TimeUnit.SECONDS), is(true));
            assertThat("Push response headers are valid", pushResponseHeaders.await(5, TimeUnit.SECONDS), is(true));
        }
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

        sendRequest(session1, createHeaders(cssResource), null, null, false);

        // Create another client, and perform the same request for the main resource, we expect the css being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version, address, null);
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
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
        Session session2 = startClient(version, address, null);
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                Assert.assertTrue(pushInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version)).value().endsWith("" +
                        ".css"));
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
        Session session2 = startClient(version, address, null);
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
                    {
                        return new Adapter()
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

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }

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
        if (validateUriHeader(headers))
            pushSynHeadersValid.countDown();
    }

    private boolean validateHeader(Fields headers, String name, String expectedValue)
    {
        Fields.Field header = headers.get(name);
        if (header != null && expectedValue.equals(header.value()))
            return true;
        System.out.println(name + " not valid! Expected: " + expectedValue + " headers received:" + headers);
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
        requestHeaders.put("accept-encoding", "gzip");
        requestHeaders.put(HTTPSPDYHeader.METHOD.name(version), "GET");
        requestHeaders.put(HTTPSPDYHeader.URI.name(version), resource);
        requestHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        requestHeaders.put(HTTPSPDYHeader.SCHEME.name(version), "http");
        requestHeaders.put(HTTPSPDYHeader.HOST.name(version), "localhost:" + connector.getLocalPort());
        return requestHeaders;
    }
}
