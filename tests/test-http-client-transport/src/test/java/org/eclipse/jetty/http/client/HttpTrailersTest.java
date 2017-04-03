//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Test;

public class HttpTrailersTest extends AbstractTest
{
    public HttpTrailersTest(Transport transport)
    {
        super(transport == Transport.FCGI ? null : transport);
    }

    @Test
    public void testRequestTrailersNoContent() throws Exception
    {
        testRequestTrailers(null);
    }

    @Test
    public void testRequestTrailersWithContent() throws Exception
    {
        testRequestTrailers("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
    }

    private void testRequestTrailers(byte[] content) throws Exception
    {
        String trailerName = "Trailer";
        String trailerValue = "value";
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);

                // Read the content first.
                ServletInputStream input = jettyRequest.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                // Now the trailers can be accessed.
                HttpFields trailers = jettyRequest.getTrailers();
                Assert.assertNotNull(trailers);
                Assert.assertEquals(trailerValue, trailers.get(trailerName));
            }
        });

        HttpFields trailers = new HttpFields();
        trailers.put(trailerName, trailerValue);

        HttpRequest request = (HttpRequest)client.newRequest(newURI());
        request = request.trailers(() -> trailers);
        if (content != null)
            request.method(HttpMethod.POST).content(new BytesContentProvider(content));
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testEmptyRequestTrailers() throws Exception
    {
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);

                // Read the content first.
                ServletInputStream input = jettyRequest.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                // Now the trailers can be accessed.
                HttpFields trailers = jettyRequest.getTrailers();
                Assert.assertNull(trailers);
            }
        });

        HttpFields trailers = new HttpFields();
        HttpRequest request = (HttpRequest)client.newRequest(newURI());
        request = request.trailers(() -> trailers);
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testResponseTrailersNoContent() throws Exception
    {
        testResponseTrailers(null);
    }

    @Test
    public void testResponseTrailersWithContent() throws Exception
    {
        testResponseTrailers("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));
    }

    private void testResponseTrailers(byte[] content) throws Exception
    {
        String trailerName = "Trailer";
        String trailerValue = "value";
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);

                HttpFields trailers = new HttpFields();
                trailers.put(trailerName, trailerValue);

                Response jettyResponse = (Response)response;
                jettyResponse.setTrailers(() -> trailers);
                if (content != null)
                    response.getOutputStream().write(content);
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>(new Throwable("no_success"));
        ContentResponse response = client.newRequest(newURI())
                .onResponseSuccess(r ->
                {
                    try
                    {
                        HttpResponse httpResponse = (HttpResponse)r;
                        HttpFields trailers = httpResponse.getTrailers();
                        Assert.assertNotNull(trailers);
                        Assert.assertEquals(trailerValue, trailers.get(trailerName));
                        failure.set(null);
                    }
                    catch (Throwable x)
                    {
                        failure.set(x);
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertNull(failure.get());
    }

    @Test
    public void testEmptyResponseTrailers() throws Exception
    {
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);

                HttpFields trailers = new HttpFields();

                Response jettyResponse = (Response)response;
                jettyResponse.setTrailers(() -> trailers);
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>(new Throwable("no_success"));
        ContentResponse response = client.newRequest(newURI())
                .onResponseSuccess(r ->
                {
                    try
                    {
                        HttpResponse httpResponse = (HttpResponse)r;
                        HttpFields trailers = httpResponse.getTrailers();
                        Assert.assertNull(trailers);
                        failure.set(null);
                    }
                    catch (Throwable x)
                    {
                        failure.set(x);
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertNull(failure.get());
    }
}
