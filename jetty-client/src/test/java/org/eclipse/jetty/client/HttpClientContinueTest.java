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

package org.eclipse.jetty.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientContinueTest extends AbstractHttpClientServerTest
{
    public HttpClientContinueTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void test_Expect100Continue_WithOneContent_Respond100Continue() throws Exception
    {
        test_Expect100Continue_Respond100Continue("data1".getBytes("UTF-8"));
    }

    @Test
    public void test_Expect100Continue_WithMultipleContents_Respond100Continue() throws Exception
    {
        test_Expect100Continue_Respond100Continue("data1".getBytes("UTF-8"), "data2".getBytes("UTF-8"), "data3".getBytes("UTF-8"));
    }

    private void test_Expect100Continue_Respond100Continue(byte[]... contents) throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and copy the content back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(contents))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        int index = 0;
        byte[] responseContent = response.getContent();
        for (byte[] content : contents)
        {
            for (byte b : content)
            {
                Assert.assertEquals(b, responseContent[index++]);
            }
        }
    }

    @Test
    public void test_Expect100Continue_WithChunkedContent_Respond100Continue() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and copy the content back
                ServletInputStream input = request.getInputStream();
                // Make sure we chunk the response too
                response.flushBuffer();
                IO.copy(input, response.getOutputStream());
            }
        });

        byte[] content1 = new byte[10240];
        byte[] content2 = new byte[16384];
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content1, content2)
                {
                    @Override
                    public long getLength()
                    {
                        return -1;
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        int index = 0;
        byte[] responseContent = response.getContent();
        for (byte b : content1)
            Assert.assertEquals(b, responseContent[index++]);
        for (byte b : content2)
            Assert.assertEquals(b, responseContent[index++]);
    }

    @Test
    public void test_Expect100Continue_WithContent_Respond417ExpectationFailed() throws Exception
    {
        test_Expect100Continue_WithContent_RespondError(417);
    }

    @Test
    public void test_Expect100Continue_WithContent_Respond413RequestEntityTooLarge() throws Exception
    {
        test_Expect100Continue_WithContent_RespondError(413);
    }

    private void test_Expect100Continue_WithContent_RespondError(final int error) throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.sendError(error);
            }
        });

        byte[] content1 = new byte[10240];
        byte[] content2 = new byte[16384];
        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content1, content2))
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        Assert.assertNotNull(result.getRequestFailure());
                        Assert.assertNull(result.getResponseFailure());
                        byte[] content = getContent();
                        Assert.assertNotNull(content);
                        Assert.assertTrue(content.length > 0);
                        Assert.assertEquals(error, result.getResponse().getStatus());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_Expect100Continue_WithContent_WithRedirect() throws Exception
    {
        final String data = "success";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().endsWith("/done"))
                {
                    response.getOutputStream().print(data);
                }
                else
                {
                    // Send 100-Continue and consume the content
                    IO.copy(request.getInputStream(), new ByteArrayOutputStream());
                    // Send a redirect
                    response.sendRedirect("/done");
                }
            }
        });

        byte[] content = new byte[10240];
        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .path("/continue")
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content))
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertFalse(result.isFailed());
                        Assert.assertEquals(200, result.getResponse().getStatus());
                        Assert.assertEquals(data, getContentAsString());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_Redirect_WithExpect100Continue_WithContent() throws Exception
    {
        // A request with Expect: 100-Continue cannot receive non-final responses like 3xx

        final String data = "success";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().endsWith("/done"))
                {
                    // Send 100-Continue and consume the content
                    IO.copy(request.getInputStream(), new ByteArrayOutputStream());
                    response.getOutputStream().print(data);
                }
                else
                {
                    // Send a redirect
                    response.sendRedirect("/done");
                }
            }
        });

        byte[] content = new byte[10240];
        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .path("/redirect")
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content))
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        Assert.assertNotNull(result.getRequestFailure());
                        Assert.assertNull(result.getResponseFailure());
                        Assert.assertEquals(302, result.getResponse().getStatus());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Slow
    @Test
    public void test_Expect100Continue_WithContent_WithResponseFailure_Before100Continue() throws Exception
    {
        final long idleTimeout = 1000;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                try
                {
                    TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        client.setIdleTimeout(idleTimeout);

        byte[] content = new byte[1024];
        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content))
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        Assert.assertNotNull(result.getRequestFailure());
                        Assert.assertNotNull(result.getResponseFailure());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(3 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Slow
    @Test
    public void test_Expect100Continue_WithContent_WithResponseFailure_After100Continue() throws Exception
    {
        final long idleTimeout = 1000;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and consume the content
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
                try
                {
                    TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        client.setIdleTimeout(idleTimeout);

        byte[] content = new byte[1024];
        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
                .content(new BytesContentProvider(content))
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        Assert.assertNull(result.getRequestFailure());
                        Assert.assertNotNull(result.getResponseFailure());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(3 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_Expect100Continue_WithContent_WithResponseFailure_During100Continue() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and consume the content
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        client.getProtocolHandlers().clear();
        client.getProtocolHandlers().add(new ContinueProtocolHandler(client)
        {
            @Override
            public Response.Listener getResponseListener()
            {
                final Response.Listener listener = super.getResponseListener();
                return new Response.Listener.Empty()
                {
                    @Override
                    public void onBegin(Response response)
                    {
                        response.abort(new Exception());
                    }

                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        listener.onFailure(response, failure);
                    }
                };
            }
        });

        try
        {
            Log.getLogger(HttpChannel.class).info("Expecting Close warning...");
            ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(true);

            byte[] content = new byte[1024];
            final CountDownLatch latch = new CountDownLatch(1);
            client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme)
            .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString())
            .content(new BytesContentProvider(content))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    Assert.assertTrue(result.isFailed());
                    Assert.assertNotNull(result.getRequestFailure());
                    Assert.assertNotNull(result.getResponseFailure());
                    latch.countDown();
                }
            });

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
        finally
        {
            ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(false);
        }
    }
}
