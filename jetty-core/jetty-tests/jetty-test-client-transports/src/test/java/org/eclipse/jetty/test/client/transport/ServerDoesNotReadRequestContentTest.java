//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.test.client.transport;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ServerDoesNotReadRequestContentTest extends AbstractTest
{
    public static Collection<Transport> transportsHTTP2()
    {
        Collection<Transport> transports = transports();
        transports.retainAll(EnumSet.of(Transport.H2C, Transport.H2));
        return transports;
    }

    @ParameterizedTest
    @MethodSource("transportsHTTP2")
    public void testServerDoesNotReadRequestContent(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not read the request content.
                // Immediately write a response.
                response.write(true, null, callback);
                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1024));
        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // With HTTP/2, the server sends a RST_STREAM(NO_ERROR) frame,
        // that should be interpreted by the client not as a failure,
        // even if the client did not finish to send the request content.
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsHTTP2")
    public void testServerDoesNotReadRequestContentWithExpect100Continue(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not read the request content.
                // Immediately write a response.
                response.write(true, null, callback);
                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1024));
        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // With HTTP/2, the server sends a RST_STREAM(NO_ERROR) frame,
        // that should be interpreted by the client not as a failure,
        // even if the client did not finish to send the request content.
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsHTTP2")
    public void testServerDoesNotReadRequestContentWithRedirect(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not read the request content.
                // Immediately write a response.
                String pathInContext = Request.getPathInContext(request);
                if ("/ok".equals(pathInContext))
                    response.write(true, null, callback);
                else
                    Response.sendRedirect(request, response, callback, HttpStatus.TEMPORARY_REDIRECT_307, "/ok", true);
                return true;
            }
        });

        IntFunction<Content.Chunk> generator = index -> switch (index)
        {
            case 0 -> Content.Chunk.from(ByteBuffer.allocate(512), false);
            default -> null;
        };
        GeneratingRequestContent content = new GeneratingRequestContent(generator);
        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // With HTTP/2, the server sends a RST_STREAM(NO_ERROR) frame,
        // that should be interpreted by the client not as a failure,
        // even if the client did not finish to send the request content.
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsHTTP2")
    public void testServerDoesNotReadRequestContentWithUnauthorized(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not read the request content.
                // Immediately write a response.
                if (request.getHeaders().contains(HttpHeader.AUTHORIZATION))
                {
                    response.write(true, null, callback);
                }
                else
                {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\"");
                    Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
                }
                return true;
            }
        });

        URI uri = newURI(transport);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, "test", "user", "password"));
        IntFunction<Content.Chunk> generator = index -> switch (index)
        {
            case 0 -> Content.Chunk.from(ByteBuffer.allocate(512), false);
            default -> null;
        };
        GeneratingRequestContent content = new GeneratingRequestContent(generator);
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // With HTTP/2, the server sends a RST_STREAM(NO_ERROR) frame,
        // that should be interpreted by the client not as a failure,
        // even if the client did not finish to send the request content.
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    private static class GeneratingRequestContent implements org.eclipse.jetty.client.Request.Content
    {
        private final IntFunction<Content.Chunk> generator;
        private final AtomicInteger index = new AtomicInteger();

        private GeneratingRequestContent(IntFunction<Content.Chunk> generator)
        {
            this.generator = generator;
        }

        @Override
        public Content.Chunk read()
        {
            return generator.apply(index.getAndIncrement());
        }

        @Override
        public void demand(Runnable demandCallback)
        {
        }

        @Override
        public void fail(Throwable failure)
        {
        }

        @Override
        public boolean rewind()
        {
            index.set(0);
            return true;
        }
    }
}
