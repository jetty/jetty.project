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

import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class HttpClientTransportOverHTTP2Test
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
