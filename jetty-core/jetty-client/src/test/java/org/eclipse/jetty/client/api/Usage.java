//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Usage
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server(8080);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Be a good HTTP citizen and read the entire request body content
                try (InputStream input = Content.Source.asInputStream(request))
                {
                    // Read, but discard results
                    IO.readBytes(input);
                }
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        });
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGETBlockingShortAPI() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        // Block to get the response
        ContentResponse response = client.GET("http://localhost:8080/foo");

        // Verify response status code
        assertEquals(200, response.getStatus());

        // Access headers
        response.getHeaders().get("Content-Length");
    }

    @Test
    public void testGETBlocking() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        // Address must be provided, it's the only thing non defaultable
        Request request = client.newRequest("localhost", 8080)
            .scheme("http")
            .method(HttpMethod.GET)
            .path("/uri")
            .version(HttpVersion.HTTP_1_1)
            .param("a", "b")
            .headers(headers -> headers.put("X-Header", "Y-value"))
            .agent("Jetty HTTP Client")
            .idleTimeout(5000, TimeUnit.MILLISECONDS)
            .timeout(20, TimeUnit.SECONDS);

        ContentResponse response = request.send();
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testGETAsync() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        final AtomicReference<Response> responseRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        client.newRequest("localhost", 8080)
            // Send asynchronously
            .send(result ->
            {
                if (result.isSucceeded())
                {
                    responseRef.set(result.getResponse());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Response response = responseRef.get();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testPOSTWithParamsShortAPI() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        // One liner to POST
        client.POST("http://localhost:8080").param("a", "€").send();
    }

    @Test
    public void testRequestListener() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        Response response = client.newRequest("localhost", 8080)
            // Add a request listener
            .listener(new Request.Listener.Adapter()
            {
                @Override
                public void onSuccess(Request request)
                {
                }
            }).send();
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testRequestWithExplicitConnectionControl() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        Request request = client.newRequest("localhost", 8080);

        // Create an explicit connection, and use try-with-resources to manage it
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        client.resolveDestination(request).newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            // Asynchronous send but using FutureResponseListener
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            // Wait for the response on the listener
            Response response = listener.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    public void testFileUpload() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        // Upload a file via POST
        Response response = client.newRequest("http://localhost:8080/uploads/")
            .method(HttpMethod.POST)
            .file(Path.of("src/test/resources/file_to_upload.txt"))
            .send();

        assertEquals(200, response.getStatus());
    }

    @Test
    public void testCookie() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        // Set a cookie to be sent in requests that match the cookie's domain
        client.getCookieStore().add(URI.create("http://localhost:8080/path"), new HttpCookie("name", "value"));

        // Send a request for the cookie's domain
        Response response = client.newRequest("localhost", 8080).send();

        assertEquals(200, response.getStatus());
    }

    @Test
    public void testBasicAuthentication() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        URI uri = URI.create("http://localhost:8080/secure");

        // Setup Basic authentication credentials for TestRealm
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, "TestRealm", "username", "password"));

        // One liner to send the request
        ContentResponse response = client.newRequest(uri).timeout(5, TimeUnit.SECONDS).send();

        assertEquals(200, response.getStatus());
    }

    @Test
    public void testFollowRedirects() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        // Do not follow redirects by default
        client.setFollowRedirects(false);

        ContentResponse response = client.newRequest("localhost", 8080)
            // Follow redirects for this request only
            .followRedirects(true)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
    }

    @Test
    public void testResponseInputStream() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        // Send asynchronously with the InputStreamResponseListener
        client.newRequest("localhost", 8080).send(listener);

        // Call to the listener's get() blocks until the headers arrived
        Response response = listener.get(5, TimeUnit.SECONDS);

        // Now check the response information that arrived to decide whether to read the content
        if (response.getStatus() == 200)
        {
            byte[] buffer = new byte[256];
            try (InputStream input = listener.getInputStream())
            {
                while (true)
                {
                    int read = input.read(buffer);
                    if (read < 0)
                        break;
                    // Do something with the bytes just read
                }
            }
        }
        else
        {
            response.abort(new Exception());
        }
    }

    @Test
    public void testRequestInputStream() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        InputStream input = new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));

        ContentResponse response = client.newRequest("localhost", 8080)
            // Provide the content as InputStream
            .body(new InputStreamRequestContent(input))
            .send();

        assertEquals(200, response.getStatus());
    }

    @Test
    public void testRequestOutputStream() throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        try (OutputStream output = content.getOutputStream())
        {
            client.newRequest("localhost", 8080)
                    .body(content)
                    .send(result -> assertEquals(200, result.getResponse().getStatus()));

            output.write(new byte[1024]);
            output.write(new byte[512]);
            output.write(new byte[256]);
            output.write(new byte[128]);
        }
    }

    @Test
    public void testProxyUsage() throws Exception
    {
        // In proxies, we receive the headers but not the content, so we must be able to send the request with
        // a lazy request content that does not block request.send(...)

        HttpClient client = new HttpClient();
        client.start();

        AtomicBoolean sendContent = new AtomicBoolean(true);
        AsyncRequestContent async = new AsyncRequestContent(ByteBuffer.wrap(new byte[]{0, 1, 2}));
        client.newRequest("localhost", 8080)
            .body(async)
            .send(new Response.Listener.Adapter()
            {
                @Override
                public void onBegin(Response response)
                {
                    if (response.getStatus() == HttpStatus.NOT_FOUND_404)
                        sendContent.set(false);
                }
            });

        Thread.sleep(100);

        if (sendContent.get())
            async.write(ByteBuffer.wrap(new byte[]{0}), Callback.NOOP);

        Thread.sleep(100);

        if (sendContent.get())
            async.write(ByteBuffer.wrap(new byte[]{0}), Callback.NOOP);

        Thread.sleep(100);

        async.close();
    }
}
