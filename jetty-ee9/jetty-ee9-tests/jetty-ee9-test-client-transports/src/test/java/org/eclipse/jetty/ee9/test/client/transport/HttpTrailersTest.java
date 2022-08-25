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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpTrailersTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testRequestTrailersNoContent(Transport transport) throws Exception
    {
        testRequestTrailers(transport, null);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testRequestTrailersWithContent(Transport transport) throws Exception
    {
        testRequestTrailers(transport, "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
    }

    private void testRequestTrailers(Transport transport, byte[] content) throws Exception
    {
        String trailerName = "Trailer";
        String trailerValue = "value";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Read the content first.
                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                assertTrue(request.isTrailerFieldsReady());

                // Now the trailers can be accessed.
                Map<String, String> trailers = request.getTrailerFields();
                assertNotNull(trailers);
                assertEquals(trailerValue, trailers.get(trailerName.toLowerCase(Locale.ENGLISH)));
            }
        });

        HttpFields trailers = HttpFields.build().put(trailerName, trailerValue).asImmutable();

        HttpRequest request = (HttpRequest)client.newRequest(newURI(transport));
        request = request.trailers(() -> trailers);
        if (content != null)
            request.method(HttpMethod.POST).body(new BytesRequestContent(content));
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testEmptyRequestTrailers(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Read the content first.
                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                assertTrue(request.isTrailerFieldsReady());

                // Now the trailers can be accessed.
                Map<String, String> trailers = request.getTrailerFields();
                assertNotNull(trailers);
                assertTrue(trailers.isEmpty());
            }
        });

        HttpFields trailers = HttpFields.EMPTY;
        HttpRequest request = (HttpRequest)client.newRequest(newURI(transport));
        request = request.trailers(() -> trailers);
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseTrailersNoContent(Transport transport) throws Exception
    {
        testResponseTrailers(transport, null);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseTrailersWithContent(Transport transport) throws Exception
    {
        testResponseTrailers(transport, "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
    }

    private void testResponseTrailers(Transport transport, byte[] content) throws Exception
    {
        AtomicBoolean firstRequest = new AtomicBoolean();
        String trailerName = "Trailer";
        String trailerValue = "value";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (firstRequest.compareAndSet(false, true))
                {
                    // Servlet spec requires applications to add the Trailer header.
                    response.setHeader("Trailer", trailerName);
                    Map<String, String> trailers = Map.of(trailerName, trailerValue);
                    response.setTrailerFields(() -> trailers);
                }
                if (content != null)
                    response.getOutputStream().write(content);
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>(new Throwable("no_success"));
        ContentResponse response = client.newRequest(newURI(transport))
            .onResponseSuccess(r ->
            {
                try
                {
                    HttpResponse httpResponse = (HttpResponse)r;
                    HttpFields trailers = httpResponse.getTrailers();
                    assertNotNull(trailers);
                    assertEquals(trailerValue, trailers.get(trailerName));
                    failure.set(null);
                }
                catch (Throwable x)
                {
                    failure.set(x);
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(failure.get());

        // Subsequent requests should not have trailers.
        response = client.newRequest(newURI(transport))
            .onResponseSuccess(r ->
            {
                try
                {
                    HttpResponse httpResponse = (HttpResponse)r;
                    assertNull(httpResponse.getTrailers());
                    failure.set(null);
                }
                catch (Throwable x)
                {
                    failure.set(x);
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(failure.get());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testEmptyResponseTrailers(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                response.setTrailerFields(Map::of);
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>(new Throwable("no_success"));
        ContentResponse response = client.newRequest(newURI(transport))
            .onResponseSuccess(r ->
            {
                try
                {
                    HttpResponse httpResponse = (HttpResponse)r;
                    HttpFields trailers = httpResponse.getTrailers();
                    assertNull(trailers);
                    failure.set(null);
                }
                catch (Throwable x)
                {
                    failure.set(x);
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(failure.get());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseTrailersWithLargeContent(Transport transport) throws Exception
    {
        byte[] content = new byte[1024 * 1024];
        new Random().nextBytes(content);
        String trailerName = "Trailer";
        String trailerValue = "value";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setTrailerFields(() -> Map.of(trailerName, trailerValue));
                // Write a large content
                response.getOutputStream().write(content);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
            .timeout(15, TimeUnit.SECONDS)
            .send(listener);
        org.eclipse.jetty.client.api.Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        InputStream input = listener.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // Read slowly.
        while (true)
        {
            int read = input.read();
            if (read < 0)
                break;
            output.write(read);
        }

        assertArrayEquals(content, output.toByteArray());

        // Wait for the request/response cycle to complete.
        listener.await(5, TimeUnit.SECONDS);

        HttpResponse httpResponse = (HttpResponse)response;
        HttpFields trailers = httpResponse.getTrailers();
        assertNotNull(trailers);
        assertEquals(trailerValue, trailers.get(trailerName));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseResetAlsoResetsTrailers(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setTrailerFields(() -> Map.of("name", "value"));
                // Fill some other response field.
                response.setHeader("name", "value");
                response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
                response.getWriter();

                // Reset the response.
                response.reset();
                // Must not throw because we have called
                // getWriter() above, since we have reset().
                response.getOutputStream();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                HttpResponse response = (HttpResponse)result.getResponse();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertNull(response.getTrailers());
                assertNull(response.getHeaders().get("name"));
                latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
