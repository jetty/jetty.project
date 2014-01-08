//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Assert;
import org.junit.Test;

public class ServerHTTPSPDYTest extends AbstractHTTPSPDYTest
{
    private static final Logger LOG = Log.getLogger(ServerHTTPSPDYTest.class);

    public ServerHTTPSPDYTest(short version)
    {
        super(version);
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("GET", httpRequest.getMethod());
                assertEquals(path, target);
                assertEquals(path, httpRequest.getRequestURI());
                assertThat("accept-encoding is set to gzip, even if client didn't set it",
                        httpRequest.getHeader("accept-encoding"), containsString("gzip"));
                assertThat(httpRequest.getHeader("host"), is("localhost:" + connector.getLocalPort()));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getLocalPort(), version, "GET", path);
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertTrue(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertThat(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"), is(true));
                assertThat(replyHeaders.get(HttpHeader.SERVER.asString()), is(notNullValue()));
                assertThat(replyHeaders.get(HttpHeader.X_POWERED_BY.asString()), is(notNullValue()));
                replyLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithQueryString() throws Exception
    {
        final String path = "/foo";
        final String query = "p=1";
        final String uri = path + "?" + query;
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("GET", httpRequest.getMethod());
                assertEquals(path, target);
                assertEquals(path, httpRequest.getRequestURI());
                assertEquals(query, httpRequest.getQueryString());
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", uri);
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertTrue(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithCookies() throws Exception
    {
        final String path = "/foo";
        final String uri = path;
        final String cookie1 = "cookie1";
        final String cookie2 = "cookie2";
        final String cookie1Value = "cookie 1 value";
        final String cookie2Value = "cookie 2 value";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.addCookie(new Cookie(cookie1, cookie1Value));
                httpResponse.addCookie(new Cookie(cookie2, cookie2Value));
                assertThat("method is GET", httpRequest.getMethod(), is("GET"));
                assertThat("target is /foo", target, is(path));
                assertThat("requestUri is /foo", httpRequest.getRequestURI(), is(path));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", uri);
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertThat("isClose is true", replyInfo.isClose(), is(true));
                Fields replyHeaders = replyInfo.getHeaders();
                assertThat("response code is 200 OK", replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue()
                        .contains("200"), is(true));
                assertThat(replyInfo.getHeaders().get("Set-Cookie").getValues().get(0), is(cookie1 + "=\"" + cookie1Value +
                        "\";Version=1"));
                assertThat(replyInfo.getHeaders().get("Set-Cookie").getValues().get(1), is(cookie2 + "=\"" + cookie2Value +
                        "\";Version=1"));
                replyLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHEAD() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("HEAD", httpRequest.getMethod());
                assertEquals(path, target);
                assertEquals(path, httpRequest.getRequestURI());
                httpResponse.getWriter().write("body that shouldn't be sent on a HEAD request");
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "HEAD", path);
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertTrue(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                fail("HEAD request shouldn't send any data");
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithDelayedContentBody() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                // don't read the request body, reply immediately
                request.setHandled(true);
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", path);
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        assertTrue(replyInfo.isClose());
                        Fields replyHeaders = replyInfo.getHeaders();
                        assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                        replyLatch.countDown();
                    }
                });
        stream.data(new StringDataInfo("a", false));
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        stream.data(new StringDataInfo("b", true));
    }

    @Test
    public void testPOSTWithParameters() throws Exception
    {
        final String path = "/foo";
        final String data = "a=1&b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("POST", httpRequest.getMethod());
                assertEquals("1", httpRequest.getParameter("a"));
                assertEquals("2", httpRequest.getParameter("b"));
                assertNotNull(httpRequest.getRemoteHost());
                assertNotNull(httpRequest.getRemotePort());
                assertNotNull(httpRequest.getRemoteAddr());
                assertNotNull(httpRequest.getLocalPort());
                assertNotNull(httpRequest.getLocalName());
                assertNotNull(httpRequest.getLocalAddr());
                assertNotNull(httpRequest.getServerPort());
                assertNotNull(httpRequest.getServerName());
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", path);
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        assertTrue(replyInfo.isClose());
                        Fields replyHeaders = replyInfo.getHeaders();
                        assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                        replyLatch.countDown();
                    }
                });
        stream.data(new StringDataInfo(data, true));

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParametersInTwoFramesTwoReads() throws Exception
    {
        final String path = "/foo";
        final String data1 = "a=1&";
        final String data2 = "b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("POST", httpRequest.getMethod());
                assertEquals("1", httpRequest.getParameter("a"));
                assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", path);
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        assertTrue(replyInfo.isClose());
                        Fields replyHeaders = replyInfo.getHeaders();
                        assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                        replyLatch.countDown();
                    }
                });
        // Sleep between the data frames so that they will be read in 2 reads
        stream.data(new StringDataInfo(data1, false));
        Thread.sleep(1000);
        stream.data(new StringDataInfo(data2, true));

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParametersInTwoFramesOneRead() throws Exception
    {
        final String path = "/foo";
        final String data1 = "a=1&";
        final String data2 = "b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("POST", httpRequest.getMethod());
                assertEquals("1", httpRequest.getParameter("a"));
                assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", path);
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertTrue(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.toString(), replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }
        });

        // Send the data frames consecutively, so the server reads both frames in one read
        stream.data(new StringDataInfo(data1, false));
        stream.data(new StringDataInfo(data2, true));

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseContent() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data.getBytes(StandardCharsets.UTF_8));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                assertTrue(dataInfo.isClose());
                assertEquals(data, dataInfo.asString(StandardCharsets.UTF_8, true));
                dataLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithOneByteResponseContent() throws Exception
    {
        final char data = 'x';
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data);
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                assertTrue(dataInfo.isClose());
                byte[] bytes = dataInfo.asBytes(true);
                assertEquals(1, bytes.length);
                assertEquals(data, bytes[0]);
                dataLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseContentInTwoChunks() throws Exception
    {
        final String data1 = "0123456789ABCDEF";
        final String data2 = "FEDCBA9876543210";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data1.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.write(data2.getBytes(StandardCharsets.UTF_8));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replyFrames = new AtomicInteger();
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertEquals(1, replyFrames.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int data = dataFrames.incrementAndGet();
                assertTrue(data >= 1 && data <= 2);
                if (data == 1)
                    assertEquals(data1, dataInfo.asString(StandardCharsets.UTF_8, true));
                else
                    assertEquals(data2, dataInfo.asString(StandardCharsets.UTF_8, true));
                dataLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInOneWrite() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'x');
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data);
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger contentBytes = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                contentBytes.addAndGet(dataInfo.asByteBuffer(true).remaining());
                if (dataInfo.isClose())
                {
                    assertEquals(data.length, contentBytes.get());
                    dataLatch.countDown();
                }
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInMultipleWrites() throws Exception
    {
        final byte[] data = new byte[4 * 1024];
        Arrays.fill(data, (byte)'x');
        final int writeTimes = 16;
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                for (int i = 0; i < writeTimes; i++)
                {
                    output.write(data);
                }
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger contentBytes = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                contentBytes.addAndGet(dataInfo.asByteBuffer(true).remaining());
                if (dataInfo.isClose())
                {
                    assertEquals(data.length * writeTimes, contentBytes.get());
                    dataLatch.countDown();
                }
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInTwoWrites() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'y');
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data);
                output.write(data);
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger contentBytes = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                contentBytes.addAndGet(dataInfo.asByteBuffer(true).remaining());
                if (dataInfo.isClose())
                {
                    assertEquals(2 * data.length, contentBytes.get());
                    dataLatch.countDown();
                }
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithOutputStreamFlushedAndClosed() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                ByteBuffer byteBuffer = dataInfo.asByteBuffer(true);
                while (byteBuffer.hasRemaining())
                    buffer.write(byteBuffer.get());
                if (dataInfo.isClose())
                {
                    assertEquals(data, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                    dataLatch.countDown();
                }
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithResponseResetBuffer() throws Exception
    {
        final String data1 = "0123456789ABCDEF";
        final String data2 = "FEDCBA9876543210";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                // Write some
                output.write(data1.getBytes(StandardCharsets.UTF_8));
                // But then change your mind and reset the buffer
                httpResponse.resetBuffer();
                output.write(data2.getBytes(StandardCharsets.UTF_8));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                ByteBuffer byteBuffer = dataInfo.asByteBuffer(true);
                while (byteBuffer.hasRemaining())
                    buffer.write(byteBuffer.get());
                if (dataInfo.isClose())
                {
                    assertEquals(data2, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                    dataLatch.countDown();
                }
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithRedirect() throws Exception
    {
        final String suffix = "/redirect";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                String location = httpResponse.encodeRedirectURL(String.format("%s://%s:%d%s",
                        request.getScheme(), request.getLocalAddr(), request.getLocalPort(), target + suffix));
                httpResponse.sendRedirect(location);
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertEquals(1, replies.incrementAndGet());
                assertTrue(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("302"));
                assertTrue(replyHeaders.get("location").getValue().endsWith(suffix));
                replyLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSendError() throws Exception
    {
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND);
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertEquals(1, replies.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("404"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithException() throws Exception
    {
        StdErrLog log = StdErrLog.getLogger(HttpChannel.class);
        log.setHideStacks(true);

        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                throw new NullPointerException("thrown_explicitly_by_the_test");
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertEquals(1, replies.incrementAndGet());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("500"));
                replyLatch.countDown();
                if (replyInfo.isClose())
                    latch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                if (dataInfo.isClose())
                    latch.countDown();
            }
        });
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        log.setHideStacks(false);
    }

    @Test
    public void testGETWithSmallResponseContentChunked() throws Exception
    {
        final String pangram1 = "the quick brown fox jumps over the lazy dog";
        final String pangram2 = "qualche vago ione tipo zolfo, bromo, sodio";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setHeader("Transfer-Encoding", "chunked");
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(pangram1.getBytes(StandardCharsets.UTF_8));
                httpResponse.setHeader("EXTRA", "X");
                output.flush();
                output.write(pangram2.getBytes(StandardCharsets.UTF_8));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replyFrames = new AtomicInteger();
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertEquals(1, replyFrames.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                assertTrue(replyHeaders.get("extra").getValue().contains("X"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int count = dataFrames.incrementAndGet();
                if (count == 1)
                {
                    Assert.assertFalse(dataInfo.isClose());
                    assertEquals(pangram1, dataInfo.asString(StandardCharsets.UTF_8, true));
                }
                else if (count == 2)
                {
                    assertTrue(dataInfo.isClose());
                    assertEquals(pangram2, dataInfo.asString(StandardCharsets.UTF_8, true));
                }
                dataLatch.countDown();
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithMediumContentAsBufferByPassed() throws Exception
    {
        final byte[] data = new byte[2048];

        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                request.getResponse().getHttpOutput().sendContent(ByteBuffer.wrap(data));
                handlerLatch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replyFrames = new AtomicInteger();
            private final AtomicInteger contentLength = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertEquals(1, replyFrames.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                contentLength.addAndGet(dataInfo.asBytes(true).length);
                if (dataInfo.isClose())
                {
                    Assert.assertEquals(data.length, contentLength.get());
                    dataLatch.countDown();
                }
            }
        });
        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithMultipleMediumContentByPassed() throws Exception
    {
        final byte[] data = new byte[2048];
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                // The sequence of write/flush/write/write below triggers a condition where
                // HttpGenerator._bypass is set to true on the second write(), and the
                // third write causes an infinite spin loop on the third write().
                request.setHandled(true);
                OutputStream output = httpResponse.getOutputStream();
                output.write(data);
                output.flush();
                output.write(data);
                output.write(data);
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final AtomicInteger contentLength = new AtomicInteger();
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.available());
                contentLength.addAndGet(dataInfo.length());
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        assertEquals(3 * data.length, contentLength.get());
    }

    @Test
    public void testPOSTThenSuspendRequestThenReadOneChunkThenComplete() throws Exception
    {
        final byte[] data = new byte[2000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);

                final Continuation continuation = ContinuationSupport.getContinuation(request);
                continuation.suspend();

                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            readRequestData(request, data.length);
                            continuation.complete();
                            latch.countDown();
                        }
                        catch (IOException x)
                        {
                            x.printStackTrace();
                        }
                    }
                }.start();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(data, true));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenSuspendExpire() throws Exception
    {
        final CountDownLatch dispatchedAgainAfterExpire = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                final Continuation continuation = ContinuationSupport.getContinuation(request);
                if (continuation.isInitial())
                {
                    continuation.setTimeout(1000);
                    continuation.suspend();
                }
                else
                {
                    dispatchedAgainAfterExpire.countDown();
                }

            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, true, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }
        });

        assertTrue("Not dispatched again after expire", dispatchedAgainAfterExpire.await(5,
                TimeUnit.SECONDS));
        assertTrue("Reply not sent", replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenSuspendExpireWithRequestData() throws Exception
    {
        final byte[] data = new byte[2000];
        final CountDownLatch dispatchedAgainAfterExpire = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                final Continuation continuation = ContinuationSupport.getContinuation(request);
                if (continuation.isInitial())
                {
                    readRequestData(request, data.length);
                    continuation.setTimeout(1000);
                    continuation.suspend();
                }
                else
                {
                    dispatchedAgainAfterExpire.countDown();
                }

            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(data, true));

        assertTrue("Not dispatched again after expire", dispatchedAgainAfterExpire.await(5,
                TimeUnit.SECONDS));
        assertTrue("Reply not sent", replyLatch.await(5, TimeUnit.SECONDS));
    }

    private void readRequestData(Request request, int expectedDataLength) throws IOException
    {
        InputStream input = request.getInputStream();
        byte[] buffer = new byte[512];
        int read = 0;
        while (read < expectedDataLength)
            read += input.read(buffer);
    }

    @Test
    public void testPOSTThenSuspendRequestThenReadTwoChunksThenComplete() throws Exception
    {
        final byte[] data = new byte[2000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);

                final Continuation continuation = ContinuationSupport.getContinuation(request);
                continuation.suspend();

                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            InputStream input = request.getInputStream();
                            byte[] buffer = new byte[512];
                            int read = 0;
                            while (read < 2 * data.length)
                                read += input.read(buffer);
                            continuation.complete();
                            latch.countDown();
                        }
                        catch (IOException x)
                        {
                            x.printStackTrace();
                        }
                    }
                }.start();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", "/foo");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                replyLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(data, false));
        stream.data(new BytesDataInfo(data, true));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenSuspendRequestThenResumeThenRespond() throws Exception
    {
        final byte[] data = new byte[1000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);

                final Continuation continuation = ContinuationSupport.getContinuation(request);

                if (continuation.isInitial())
                {
                    InputStream input = request.getInputStream();
                    byte[] buffer = new byte[256];
                    int read = 0;
                    while (read < data.length)
                        read += input.read(buffer);
                    continuation.suspend();
                    new Thread()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                TimeUnit.SECONDS.sleep(1);
                                continuation.resume();
                                latch.countDown();
                            }
                            catch (InterruptedException x)
                            {
                                x.printStackTrace();
                            }
                        }
                    }.start();
                }
                else
                {
                    OutputStream output = httpResponse.getOutputStream();
                    output.write(data);
                }
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", "/foo");
        final CountDownLatch responseLatch = new CountDownLatch(2);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                responseLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                if (dataInfo.isClose())
                    responseLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(data, true));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenResponseWithoutReadingContent() throws Exception
    {
        final byte[] data = new byte[1000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                latch.countDown();
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "POST", "/foo");
        final CountDownLatch responseLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"));
                responseLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(data, false));
        stream.data(new BytesDataInfo(5, TimeUnit.SECONDS, data, true));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testIdleTimeout() throws Exception
    {
        final int idleTimeout = 500;
        final CountDownLatch timeoutReceivedLatch = new CountDownLatch(1);

        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
                request.setHandled(true);
            }
        }, 30000), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/");
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, true, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onFailure(Stream stream, Throwable x)
                    {
                        assertThat("we got a TimeoutException", x, instanceOf(TimeoutException.class));
                        timeoutReceivedLatch.countDown();
                    }
                });
        stream.setIdleTimeout(idleTimeout);

        assertThat("idle timeout hit", timeoutReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testIdleTimeoutSetOnConnectionOnly() throws Exception
    {
        final int idleTimeout = 500;
        final CountDownLatch timeoutReceivedLatch = new CountDownLatch(1);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
                request.setHandled(true);
            }
        }, idleTimeout), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/");
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, true, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onFailure(Stream stream, Throwable x)
                    {
                        assertThat("we got a TimeoutException", x, instanceOf(TimeoutException.class));
                        timeoutReceivedLatch.countDown();
                    }
                });

        assertThat("idle timeout hit", timeoutReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testSingleStreamIdleTimeout() throws Exception
    {
        final int idleTimeout = 500;
        final CountDownLatch timeoutReceivedLatch = new CountDownLatch(1);
        final CountDownLatch replyReceivedLatch = new CountDownLatch(3);
        Session session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, final Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                if ("true".equals(request.getHeader("slow")))
                {
                    try
                    {
                        Thread.sleep(2 * idleTimeout);
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                }
                request.setHandled(true);
            }
        }, idleTimeout), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/");
        Fields slowHeaders = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET", "/");
        slowHeaders.add("slow", "true");
        sendSingleRequestThatIsNotExpectedToTimeout(replyReceivedLatch, session, headers);
        session.syn(new SynInfo(5, TimeUnit.SECONDS, slowHeaders, true, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onFailure(Stream stream, Throwable x)
                    {
                        assertThat("we got a TimeoutException", x, instanceOf(TimeoutException.class));
                        timeoutReceivedLatch.countDown();
                    }
                });
        Thread.sleep(idleTimeout / 2);
        sendSingleRequestThatIsNotExpectedToTimeout(replyReceivedLatch, session, headers);
        Thread.sleep(idleTimeout / 2);
        sendSingleRequestThatIsNotExpectedToTimeout(replyReceivedLatch, session, headers);
        assertThat("idle timeout hit", timeoutReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("received replies on 3 non idle requests", replyReceivedLatch.await(5, TimeUnit.SECONDS),
                is(true));
    }

    private void sendSingleRequestThatIsNotExpectedToTimeout(final CountDownLatch replyReceivedLatch, Session session, Fields headers) throws ExecutionException, InterruptedException, TimeoutException
    {
        session.syn(new SynInfo(5, TimeUnit.SECONDS, headers, true, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        replyReceivedLatch.countDown();
                    }
                });
    }

}
