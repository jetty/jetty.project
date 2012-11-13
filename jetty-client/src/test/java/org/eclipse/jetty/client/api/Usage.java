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

package org.eclipse.jetty.client.api;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.util.BlockingResponseListener;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.PathContentProvider;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class Usage
{
    @Test
    public void testGETBlocking_ShortAPI() throws Exception
    {
        HttpClient client = new HttpClient();
        Future<ContentResponse> responseFuture = client.GET("http://localhost:8080/foo");
        Response response = responseFuture.get();
        Assert.assertEquals(200, response.getStatus());
        // Headers abstraction needed for:
        // 1. case insensitivity
        // 2. multi values
        // 3. value conversion
        // Reuse SPDY's ?
        response.getHeaders().get("Content-Length");
    }

    @Test
    public void testGETBlocking() throws Exception
    {
        HttpClient client = new HttpClient();
        // Address must be provided, it's the only thing non defaultable
        Request request = client.newRequest("localhost", 8080)
                .scheme("https")
                .method(HttpMethod.GET)
                .path("/uri")
                .version(HttpVersion.HTTP_1_1)
                .param("a", "b")
                .header("X-Header", "Y-value")
                .agent("Jetty HTTP Client")
//                .decoder(null)
                .content(null)
                .idleTimeout(5000L);
        Future<ContentResponse> responseFuture = request.send();
        Response response = responseFuture.get();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testGETAsync() throws Exception
    {
        HttpClient client = new HttpClient();
        final AtomicReference<Response> responseRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", 8080).send(new Response.Listener.Empty()
        {
            @Override
            public void onSuccess(Response response)
            {
                responseRef.set(response);
                latch.countDown();
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Response response = responseRef.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testPOSTWithParams() throws Exception
    {
        HttpClient client = new HttpClient();
        client.POST("http://localhost:8080").param("a", "\u20AC").send();
    }

    @Test
    public void testRequestListener() throws Exception
    {
        HttpClient client = new HttpClient();
        Response response = client.newRequest("localhost", 8080)
                .listener(new Request.Listener.Empty()
                {
                    @Override
                    public void onSuccess(Request request)
                    {
                    }
                }).send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testRequestWithExplicitConnectionControl() throws Exception
    {
        HttpClient client = new HttpClient();
        try (Connection connection = client.getDestination("http", "localhost", 8080).newConnection().get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest("localhost", 8080);
            BlockingResponseListener listener = new BlockingResponseListener(request);
            connection.send(request, listener);
            Response response = listener.get(5, TimeUnit.SECONDS);
            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatus());
        }
    }

    @Test
    public void testFileUpload() throws Exception
    {
        HttpClient client = new HttpClient();
        Response response = client.newRequest("localhost", 8080)
                .content(new PathContentProvider(Paths.get(""))).send().get();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testCookie() throws Exception
    {
        HttpClient client = new HttpClient();
        client.getCookieStore().addCookie(client.getDestination("http", "host", 8080), new HttpCookie("name", "value"));
        Response response = client.newRequest("host", 8080).send().get();
        Assert.assertEquals(200, response.getStatus());
    }

//    @Test
//    public void testAuthentication() throws Exception
//    {
//        HTTPClient client = new HTTPClient();
//        client.newRequest("localhost", 8080).authentication(new Authentication.Kerberos()).build().send().get().status(); // 200
//    }

    @Test
    public void testFollowRedirects() throws Exception
    {
        HttpClient client = new HttpClient();
        client.setFollowRedirects(false);
        client.newRequest("localhost", 8080).followRedirects(true).send().get().getStatus(); // 200
    }

    @Test
    public void testResponseInputStream() throws Exception
    {
        HttpClient client = new HttpClient();
        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", 8080).send(listener);
        // Call to get() blocks until the headers arrived
        Response response = listener.get(5, TimeUnit.SECONDS);
        if (response.getStatus() == 200)
        {
            // Solution 1: use input stream
            byte[] buffer = new byte[256];
            try (InputStream input = listener.getInputStream())
            {
                while (true)
                {
                    int read = input.read(buffer);
                    if (read < 0)
                        break;
                    // No need for output stream; for example, parse bytes
                }
            }
        }
        else
        {
            response.abort(new Exception());
        }
    }

//    @Test
//    public void testResponseOutputStream() throws Exception
//    {
//        HttpClient client = new HttpClient();
//
//        try (FileOutputStream output = new FileOutputStream(""))
//        {
//            OutputStreamResponseListener listener = new OutputStreamResponseListener(output);
//        client.newRequest("localhost", 8080).send(listener);
//        // Call to get() blocks until the headers arrived
//        Response response = listener.get(5, TimeUnit.SECONDS);
//        if (response.status() == 200)
//        {
//            // Solution 2: write to output stream
//            {
//                listener.writeTo(output);
//            }
//        }
//        else
//        {
//            response.abort();
//        }
//    }
}
