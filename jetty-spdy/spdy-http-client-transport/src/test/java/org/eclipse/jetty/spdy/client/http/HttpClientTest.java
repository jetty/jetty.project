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

package org.eclipse.jetty.spdy.client.http;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientTest extends AbstractHttpClientServerTest
{
    public HttpClientTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void test_GET_ResponseWithoutContent() throws Exception
    {
        start(new EmptyServerHandler());

        Response response = client.GET(scheme + "://localhost:" + connector.getLocalPort());

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_GET_ResponseWithContent() throws Exception
    {
        final byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.getOutputStream().write(data);
                baseRequest.setHandled(true);
            }
        });

        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort());

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        byte[] content = response.getContent();
        Assert.assertArrayEquals(data, content);
    }

    @Test
    public void test_GET_WithParameters_ResponseWithContent() throws Exception
    {
        final String paramName1 = "a";
        final String paramName2 = "b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String paramValue1 = request.getParameter(paramName1);
                output.write(paramValue1.getBytes("UTF-8"));
                String paramValue2 = request.getParameter(paramName2);
                Assert.assertEquals("", paramValue2);
                output.write("empty".getBytes("UTF-8"));
                baseRequest.setHandled(true);
            }
        });

        String value1 = "\u20AC";
        String paramValue1 = URLEncoder.encode(value1, "UTF-8");
        String query = paramName1 + "=" + paramValue1 + "&" + paramName2;
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort() + "/?" + query);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), "UTF-8");
        Assert.assertEquals(value1 + "empty", content);
    }

    @Test
    public void test_GET_WithParametersMultiValued_ResponseWithContent() throws Exception
    {
        final String paramName1 = "a";
        final String paramName2 = "b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                ServletOutputStream output = response.getOutputStream();
                String[] paramValues1 = request.getParameterValues(paramName1);
                for (String paramValue : paramValues1)
                    output.write(paramValue.getBytes("UTF-8"));
                String paramValue2 = request.getParameter(paramName2);
                output.write(paramValue2.getBytes("UTF-8"));
                baseRequest.setHandled(true);
            }
        });

        String value11 = "\u20AC";
        String value12 = "\u20AA";
        String value2 = "&";
        String paramValue11 = URLEncoder.encode(value11, "UTF-8");
        String paramValue12 = URLEncoder.encode(value12, "UTF-8");
        String paramValue2 = URLEncoder.encode(value2, "UTF-8");
        String query = paramName1 + "=" + paramValue11 + "&" + paramName1 + "=" + paramValue12 + "&" + paramName2 + "=" + paramValue2;
        ContentResponse response = client.GET(scheme + "://localhost:" + connector.getLocalPort() + "/?" + query);

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        String content = new String(response.getContent(), "UTF-8");
        Assert.assertEquals(value11 + value12 + value2, content);
    }

    @Test
    public void test_POST_WithParameters() throws Exception
    {
        final String paramName = "a";
        final String paramValue = "\u20AC";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String value = request.getParameter(paramName);
                if (paramValue.equals(value))
                {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/plain");
                    response.getOutputStream().print(value);
                }
            }
        });

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
                .param(paramName, paramValue)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(paramValue, new String(response.getContent(), "UTF-8"));
    }

    @Test
    public void test_PUT_WithParameters() throws Exception
    {
        final String paramName = "a";
        final String paramValue = "\u20AC";
        final String encodedParamValue = URLEncoder.encode(paramValue, "UTF-8");
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String value = request.getParameter(paramName);
                if (paramValue.equals(value))
                {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("text/plain");
                    response.getOutputStream().print(value);
                }
            }
        });

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort() + "/path?" + paramName + "=" + encodedParamValue);
        ContentResponse response = client.newRequest(uri)
                .method(HttpMethod.PUT)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(paramValue, new String(response.getContent(), "UTF-8"));
    }

    @Test
    public void test_POST_WithParameters_WithContent() throws Exception
    {
        final byte[] content = {0, 1, 2, 3};
        final String paramName = "a";
        final String paramValue = "\u20AC";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String value = request.getParameter(paramName);
                if (paramValue.equals(value))
                {
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/octet-stream");
                    response.getOutputStream().write(content);
                }
            }
        });

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort() + "/?b=1")
                .param(paramName, paramValue)
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(content, response.getContent());
    }

    @Test
    public void test_POST_WithContent_NotifiesRequestContentListener() throws Exception
    {
        final byte[] content = {0, 1, 2, 3};
        start(new EmptyServerHandler());

        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
                .onRequestContent(new Request.ContentListener()
                {
                    @Override
                    public void onContent(Request request, ByteBuffer buffer)
                    {
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        if (!Arrays.equals(content, bytes))
                            request.abort(new Exception());
                    }
                })
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_POST_WithContent_TracksProgress() throws Exception
    {
        start(new EmptyServerHandler());

        final AtomicInteger progress = new AtomicInteger();
        ContentResponse response = client.POST(scheme + "://localhost:" + connector.getLocalPort())
                .onRequestContent(new Request.ContentListener()
                {
                    @Override
                    public void onContent(Request request, ByteBuffer buffer)
                    {
                        byte[] bytes = new byte[buffer.remaining()];
                        Assert.assertEquals(1, bytes.length);
                        buffer.get(bytes);
                        Assert.assertEquals(bytes[0], progress.getAndIncrement());
                    }
                })
                .content(new BytesContentProvider(new byte[]{0}, new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(5, progress.get());
    }

    @Test
    public void test_GZIP_ContentEncoding() throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader("Content-Encoding", "gzip");
                GZIPOutputStream gzipOutput = new GZIPOutputStream(response.getOutputStream());
                gzipOutput.write(data);
                gzipOutput.finish();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Slow
    @Test
    public void test_Request_IdleTimeout() throws Exception
    {
        final long idleTimeout = 1000;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        final String host = "localhost";
        final int port = connector.getLocalPort();
        try
        {
            client.newRequest(host, port)
                    .scheme(scheme)
                    .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                    .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException expected)
        {
            Assert.assertTrue(expected.getCause() instanceof TimeoutException);
        }

        // Make another request without specifying the idle timeout, should not fail
        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .timeout(3 * idleTimeout, TimeUnit.MILLISECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testSendToIPv6Address() throws Exception
    {
        start(new EmptyServerHandler());

        ContentResponse response = client.newRequest("[::1]", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void test_HEAD_With_ResponseContentLength() throws Exception
    {
        final int length = 1024;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(new byte[length]);
            }
        });

        // HEAD requests receive a Content-Length header, but do not
        // receive the content so they must handle this case properly
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.HEAD)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);

        // Perform a normal GET request to be sure the content is now read
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(length, response.getContent().length);
    }

    @Test
    public void testLongPollIsAbortedWhenClientIsStopped() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                request.startAsync();
                latch.countDown();
            }
        });

        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isFailed())
                            completeLatch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Stop the client, the complete listener must be invoked.
        client.stop();

        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }
}
