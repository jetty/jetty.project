/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.junit.Assert;
import org.junit.Test;

public class ServerHTTPSPDYTest extends AbstractHTTPSPDYTest
{
    @Test
    public void testSimpleGET() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("GET", httpRequest.getMethod());
                Assert.assertEquals(path, target);
                Assert.assertEquals(path, httpRequest.getRequestURI());
                Assert.assertEquals("localhost:" + connector.getLocalPort(), httpRequest.getHeader("host"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", path);
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithQueryString() throws Exception
    {
        final String path = "/foo";
        final String query = "p=1";
        final String uri = path + "?" + query;
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("GET", httpRequest.getMethod());
                Assert.assertEquals(path, target);
                Assert.assertEquals(path, httpRequest.getRequestURI());
                Assert.assertEquals(query, httpRequest.getQueryString());
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", uri);
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHEAD() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("HEAD", httpRequest.getMethod());
                Assert.assertEquals(path, target);
                Assert.assertEquals(path, httpRequest.getRequestURI());
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "HEAD");
        headers.put("url", path);
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParameters() throws Exception
    {
        final String path = "/foo";
        final String data = "a=1&b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("POST", httpRequest.getMethod());
                Assert.assertEquals("1", httpRequest.getParameter("a"));
                Assert.assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", path);
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        stream.data(new StringDataInfo(data, true));

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParametersInTwoFramesTwoReads() throws Exception
    {
        final String path = "/foo";
        final String data1 = "a=1&";
        final String data2 = "b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("POST", httpRequest.getMethod());
                Assert.assertEquals("1", httpRequest.getParameter("a"));
                Assert.assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", path);
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        // Sleep between the data frames so that they will be read in 2 reads
        stream.data(new StringDataInfo(data1, false));
        Thread.sleep(1000);
        stream.data(new StringDataInfo(data2, true));

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParametersInTwoFramesOneRead() throws Exception
    {
        final String path = "/foo";
        final String data1 = "a=1&";
        final String data2 = "b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("POST", httpRequest.getMethod());
                Assert.assertEquals("1", httpRequest.getParameter("a"));
                Assert.assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", path);
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.toString(), replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        // Send the data frames consecutively, so the server reads both frames in one read
        stream.data(new StringDataInfo(data1, false));
        stream.data(new StringDataInfo(data2, true));

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseContent() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data.getBytes("UTF-8"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                Assert.assertEquals(data, dataInfo.asString("UTF-8", true));
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithOneByteResponseContent() throws Exception
    {
        final char data = 'x';
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                byte[] bytes = dataInfo.asBytes(true);
                Assert.assertEquals(1, bytes.length);
                Assert.assertEquals(data, bytes[0]);
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseContentInTwoChunks() throws Exception
    {
        final String data1 = "0123456789ABCDEF";
        final String data2 = "FEDCBA9876543210";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data1.getBytes("UTF-8"));
                output.flush();
                output.write(data2.getBytes("UTF-8"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replyFrames = new AtomicInteger();
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertEquals(1, replyFrames.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int data = dataFrames.incrementAndGet();
                Assert.assertTrue(data >= 1 && data <= 2);
                if (data == 1)
                    Assert.assertEquals(data1, dataInfo.asString("UTF8", true));
                else
                    Assert.assertEquals(data2, dataInfo.asString("UTF8", true));
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInOneWrite() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'x');
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger contentBytes = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {

                contentBytes.addAndGet(dataInfo.asByteBuffer(true).remaining());
                if (dataInfo.isClose())
                {
                    Assert.assertEquals(data.length, contentBytes.get());
                    dataLatch.countDown();
                }
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInTwoWrites() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'y');
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger contentBytes = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                contentBytes.addAndGet(dataInfo.asByteBuffer(true).remaining());
                if (dataInfo.isClose())
                {
                    Assert.assertEquals(2 * data.length, contentBytes.get());
                    dataLatch.countDown();
                }
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithOutputStreamFlushedAndClosed() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data.getBytes("UTF-8"));
                output.flush();
                output.close();
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
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
                    Assert.assertEquals(data, new String(buffer.toByteArray(), Charset.forName("UTF-8")));
                    dataLatch.countDown();
                }
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithResponseResetBuffer() throws Exception
    {
        final String data1 = "0123456789ABCDEF";
        final String data2 = "FEDCBA9876543210";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                // Write some
                output.write(data1.getBytes("UTF-8"));
                // But then change your mind and reset the buffer
                httpResponse.resetBuffer();
                output.write(data2.getBytes("UTF-8"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
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
                    Assert.assertEquals(data2, new String(buffer.toByteArray(), Charset.forName("UTF-8")));
                    dataLatch.countDown();
                }
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithRedirect() throws Exception
    {
        final String suffix = "/redirect";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertEquals(1, replies.incrementAndGet());
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("302"));
                Assert.assertTrue(replyHeaders.get("location").value().endsWith(suffix));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSendError() throws Exception
    {
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND);
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertEquals(1, replies.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("404"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithException() throws Exception
    {
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                throw new NullPointerException("thrown_explicitly_by_the_test");
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertEquals(1, replies.incrementAndGet());
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("500"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseChunked() throws Exception
    {
        final String pangram1 = "the quick brown fox jumps over the lazy dog";
        final String pangram2 = "qualche vago ione tipo zolfo, bromo, sodio";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setHeader("Transfer-Encoding", "chunked");
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(pangram1.getBytes("UTF-8"));
                httpResponse.setHeader("EXTRA", "X");
                output.flush();
                output.write(pangram2.getBytes("UTF-8"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replyFrames = new AtomicInteger();
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertEquals(1, replyFrames.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                Assert.assertTrue(replyHeaders.get("extra").value().contains("X"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int count = dataFrames.incrementAndGet();
                if (count == 1)
                {
                    Assert.assertFalse(dataInfo.isClose());
                    Assert.assertEquals(pangram1, dataInfo.asString("UTF-8", true));
                }
                else if (count == 2)
                {
                    Assert.assertTrue(dataInfo.isClose());
                    Assert.assertEquals(pangram2, dataInfo.asString("UTF-8", true));
                }
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithMediumContentByPassed() throws Exception
    {
        final byte[] data = new byte[2048];
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                // We use this trick that's present in Jetty code: if we add a request attribute
                // called "org.eclipse.jetty.server.sendContent", then it will trigger the
                // content bypass that we want to test
                request.setAttribute("org.eclipse.jetty.server.sendContent", new ByteArrayBuffer(data));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger replyFrames = new AtomicInteger();
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertEquals(1, replyFrames.incrementAndGet());
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertEquals(1, dataFrames.incrementAndGet());
                Assert.assertTrue(dataInfo.isClose());
                Assert.assertArrayEquals(data, dataInfo.asBytes(true));
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenSuspendRequestThenReadOneChunkThenComplete() throws Exception
    {
        final byte[] data = new byte[2000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
                            while (read < data.length)
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        stream.data(new BytesDataInfo(data, true));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenSuspendRequestThenReadTwoChunksThenComplete() throws Exception
    {
        final byte[] data = new byte[2000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        stream.data(new BytesDataInfo(data, false));
        stream.data(new BytesDataInfo(data, true));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTThenSuspendRequestThenResumeThenRespond() throws Exception
    {
        final byte[] data = new byte[1000];
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
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
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "/foo");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch responseLatch = new CountDownLatch(2);
        Stream stream = session.syn(new SynInfo(headers, false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                responseLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                if (dataInfo.isClose())
                    responseLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        stream.data(new BytesDataInfo(data, true));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
