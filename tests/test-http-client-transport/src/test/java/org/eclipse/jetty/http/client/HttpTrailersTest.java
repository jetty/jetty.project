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

package org.eclipse.jetty.http.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.http.client.Transport.FCGI;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpTrailersTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        Assumptions.assumeTrue(transport != FCGI);
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRequestTrailersNoContent(Transport transport) throws Exception
    {
        init(transport);
        testRequestTrailers(null);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRequestTrailersWithContent(Transport transport) throws Exception
    {
        init(transport);
        testRequestTrailers("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
    }

    private void testRequestTrailers(byte[] content) throws Exception
    {
        String trailerName = "Trailer";
        String trailerValue = "value";
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Read the content first.
                ServletInputStream input = jettyRequest.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                // Now the trailers can be accessed.
                HttpFields trailers = jettyRequest.getTrailerHttpFields();
                assertNotNull(trailers);
                assertEquals(trailerValue, trailers.get(trailerName));
            }
        });

        HttpFields trailers = HttpFields.build().put(trailerName, trailerValue).asImmutable();

        HttpRequest request = (HttpRequest)scenario.client.newRequest(scenario.newURI());
        request = request.trailers(() -> trailers);
        if (content != null)
            request.method(HttpMethod.POST).body(new BytesRequestContent(content));
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testEmptyRequestTrailers(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Read the content first.
                ServletInputStream input = jettyRequest.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                // Now the trailers can be accessed.
                HttpFields trailers = jettyRequest.getTrailerHttpFields();
                assertNull(trailers);
            }
        });

        HttpFields trailers = HttpFields.EMPTY;
        HttpRequest request = (HttpRequest)scenario.client.newRequest(scenario.newURI());
        request = request.trailers(() -> trailers);
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testResponseTrailersNoContent(Transport transport) throws Exception
    {
        init(transport);
        testResponseTrailers(null);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testResponseTrailersWithContent(Transport transport) throws Exception
    {
        init(transport);
        testResponseTrailers("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
    }

    private void testResponseTrailers(byte[] content) throws Exception
    {
        AtomicBoolean once = new AtomicBoolean();
        String trailerName = "Trailer";
        String trailerValue = "value";
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                Response jettyResponse = jettyRequest.getResponse();

                if (once.compareAndSet(false, true))
                {
                    HttpFields trailers = HttpFields.build().put(trailerName, trailerValue);
                    jettyResponse.setTrailers(() -> trailers);
                }

                if (content != null)
                    response.getOutputStream().write(content);
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>(new Throwable("no_success"));
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
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
        response = scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testEmptyResponseTrailers(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                HttpFields trailers = HttpFields.build();
                response.setTrailerFields(() ->
                    trailers.stream().collect(Collectors.toMap(HttpField::getName, HttpField::getValue)));
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>(new Throwable("no_success"));
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testResponseTrailersWithLargeContent(Transport transport) throws Exception
    {
        byte[] content = new byte[1024 * 1024];
        new Random().nextBytes(content);
        String trailerName = "Trailer";
        String trailerValue = "value";
        init(transport);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                HttpFields trailers = HttpFields.build().put(trailerName, trailerValue);
                response.setTrailerFields(() ->
                    trailers.stream().collect(Collectors.toMap(HttpField::getName, HttpField::getValue)));

                // Write a large content
                response.getOutputStream().write(content);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testResponseResetAlsoResetsTrailers(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                Response jettyResponse = jettyRequest.getResponse();
                HttpFields trailers = HttpFields.build().put("name", "value");
                jettyResponse.setTrailers(() -> trailers);
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
        scenario.client.newRequest(scenario.newURI())
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
