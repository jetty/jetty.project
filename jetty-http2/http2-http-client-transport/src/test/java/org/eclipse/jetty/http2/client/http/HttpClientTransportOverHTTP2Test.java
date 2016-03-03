//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class HttpClientTransportOverHTTP2Test extends AbstractTest
{
    @Test
    public void testPropertiesAreForwarded() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), null);
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);
        httpClient.setConnectTimeout(13);
        httpClient.setIdleTimeout(17);

        httpClient.start();

        Assert.assertTrue(http2Client.isStarted());
        Assert.assertSame(httpClient.getExecutor(), http2Client.getExecutor());
        Assert.assertSame(httpClient.getScheduler(), http2Client.getScheduler());
        Assert.assertSame(httpClient.getByteBufferPool(), http2Client.getByteBufferPool());
        Assert.assertEquals(httpClient.getConnectTimeout(), http2Client.getConnectTimeout());
        Assert.assertEquals(httpClient.getIdleTimeout(), http2Client.getIdleTimeout());

        httpClient.stop();

        Assert.assertTrue(http2Client.isStopped());
    }

    @Test
    public void testRequestAbortSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onRequestCommit(request -> request.abort(new Exception("explicitly_aborted_by_test")))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testResponseAbortSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), metaData, null, false), new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        ByteBuffer data = ByteBuffer.allocate(1024);
                        stream.data(new DataFrame(stream.getId(), data, false), NOOP);
                    }
                });

                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onResponseContent((response, buffer) -> response.abort(new Exception("explicitly_aborted_by_test")))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testResponseAbortSendsGoAwayFrame() throws Exception
    {
        final String expectedContent = "{\"reason\":\"BadCertificateEnvironment\"}";
        final CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        ((HTTP2Session)stream.getSession())
                                .frames(null, callback, new GoAwayFrame(stream.getId(), 0, expectedContent.getBytes()), Frame.EMPTY_ARRAY);
                        resetLatch.countDown();
                    }
                };
            }
        });

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .content(new StringContentProvider("hello, jetty"))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertEquals(expectedContent, ((AsynchronousCloseExceptionOverHTTP2)x.getCause()).getContentToString());
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestHasHTTP2Version() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                HttpVersion version = HttpVersion.fromString(request.getProtocol());
                response.setStatus(version == HttpVersion.HTTP_2 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .onRequestBegin(request ->
                {
                    if (request.getVersion() != HttpVersion.HTTP_2)
                        request.abort(new Exception("Not a HTTP/2 request"));
                })
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Ignore
    @Test
    public void testExternalServer() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        SslContextFactory sslContextFactory = new SslContextFactory();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), sslContextFactory);
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);

        httpClient.start();

//        ContentResponse response = httpClient.GET("https://http2.akamai.com/");
        ContentResponse response = httpClient.GET("https://webtide.com/");

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        httpClient.stop();
    }
}
