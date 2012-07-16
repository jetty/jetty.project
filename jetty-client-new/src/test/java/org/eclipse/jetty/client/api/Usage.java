//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HTTPClient;
import org.eclipse.jetty.client.StreamResponseListener;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class Usage
{
    @Test
    public void testSimpleBlockingGET() throws Exception
    {
        HTTPClient client = new HTTPClient();
        Future<Response> responseFuture = client.GET("http://localhost:8080/foo");
        Response response = responseFuture.get();
        response.getStatus(); // 200
        // Headers abstraction needed for:
        // 1. case insensitivity
        // 2. multi values
        // 3. value conversion
        // Reuse SPDY's ?
        response.getHeaders().get("Content-Length").valueAsInt();
    }

    @Test
    public void testBlockingGET() throws Exception
    {
        HTTPClient client = new HTTPClient();
        // Address must be provided, it's the only thing non defaultable
        Request.Builder builder = client.builder("localhost:8080");
        Future<Response> responseFuture = builder.method("GET").uri("/").header("Origin", "localhost").build().send();
        responseFuture.get();
    }


    @Test
    public void testSimpleAsyncGET() throws Exception
    {
        HTTPClient client = new HTTPClient();
        client.builder("localhost:8080").method("GET").uri("/").header("Origin", "localhost").build().send(new Response.Listener.Adapter()
        {
            @Override
            public void onEnd(Response response)
            {
            }
        });
    }

    @Test
    public void testRequestListener() throws Exception
    {
        HTTPClient client = new HTTPClient();
        Response response = client.builder("localhost:8080")
                .method("GET")
                .uri("/")
                .listener(new Request.Listener.Adapter()
                {
                    @Override
                    public void onEnd(Request request)
                    {
                    }
                })
                .build().send(new Response.Listener.Adapter()
        {
            @Override
            public void onEnd(Response response)
            {
            }
        }).get();
        response.getStatus();
    }

    @Test
    public void testRequestWithExplicitConnectionControl() throws Exception
    {
        HTTPClient client = new HTTPClient();
        try (HTTPClient.Connection connection = client.getDestination("localhost:8080").newConnection())
        {
            Request.Builder builder = client.builder("localhost:8080");
            Request request = builder.method("GET").uri("/").header("Origin", "localhost").build();

            Future<Response> response = connection.send(request, new Response.Listener.Adapter());
            response.get().getStatus();
        }
    }

    @Test
    public void testFileUpload() throws Exception
    {
        HTTPClient client = new HTTPClient();
        Response response = client.builder("localhost:8080")
                .method("GET").uri("/").file(new File("")).build().send().get();
        response.getStatus();
    }

    @Test
    public void testCookie() throws Exception
    {
        HTTPClient client = new HTTPClient();
        client.builder("localhost:8080").cookie("key", "value").build().send().get().getStatus(); // 200
    }

    @Test
    public void testAuthentication() throws Exception
    {
        HTTPClient client = new HTTPClient();
        client.builder("localhost:8080").authentication(new Authentication.Kerberos()).build().send().get().getStatus(); // 200
    }

    @Test
    public void testFollowRedirects() throws Exception
    {
        HTTPClient client = new HTTPClient();
        client.setFollowRedirects(false);
        client.builder("localhost:8080").followRedirects(true).build().send().get().getStatus(); // 200
    }

    @Test
    public void testResponseStream() throws Exception
    {
        HTTPClient client = new HTTPClient();
        StreamResponseListener listener = new StreamResponseListener();
        client.builder("localhost:8080").build().send(listener);
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

            // Solution 2: write to output stream
            try (FileOutputStream output = new FileOutputStream(""))
            {
                listener.writeTo(output);
            }
        }
        else
        {
            response.abort();
        }
    }
}
