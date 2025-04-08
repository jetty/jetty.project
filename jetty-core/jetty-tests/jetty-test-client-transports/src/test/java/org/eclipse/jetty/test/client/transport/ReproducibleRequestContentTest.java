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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReproducibleRequestContentTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testRedirectWithReproducibleRequestContent(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (Request.getPathInContext(request).equals("/ok"))
                    Content.copy(request, response, callback);
                else
                    Response.sendRedirect(request, response, callback, HttpStatus.TEMPORARY_REDIRECT_307, "/ok", true);
                return true;
            }
        });

        String text = "hello world";
        ContentResponse response = client.newRequest(newURI(transportType))
            .method(HttpMethod.POST)
            .body(new StringRequestContent(text))
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(text, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBasicAuthenticationWithReproducibleRequestContent(TransportType transportType) throws Exception
    {
        String realm = "test-realm";
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (request.getHeaders().contains(HttpHeader.AUTHORIZATION))
                {
                    Content.copy(request, response, callback);
                }
                else
                {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"%s\"".formatted(realm));
                    Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
                }
                return true;
            }
        });

        URI uri = newURI(transportType);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "test", "secret"));

        String text = "hello world";
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.POST)
            .body(new StringRequestContent(text))
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(text, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testRedirectWithReproducibleRequestContentSplitAndDelayed(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (Request.getPathInContext(request).equals("/ok"))
                    Content.copy(request, response, callback);
                else
                    Response.sendRedirect(request, response, callback, HttpStatus.TEMPORARY_REDIRECT_307, "/ok", true);
                return true;
            }
        });

        String text1 = "hello";
        String text2 = "world";
        ReproducibleAsyncRequestContent body = new ReproducibleAsyncRequestContent();
        body.write(StandardCharsets.UTF_8.encode(text1));
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(
            client.newRequest(newURI(transportType))
                .method(HttpMethod.POST)
                .body(body)
        ).send();

        // The request was sent, wait for the server to redirect.
        Thread.sleep(1000);

        // Complete the request content.
        body.write(StandardCharsets.UTF_8.encode(text2));
        body.close();

        ContentResponse response = completable.get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(text1 + text2, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testBasicAuthenticationWithReproducibleRequestContentSplitAndDelayed(TransportType transportType) throws Exception
    {
        String realm = "test-realm";
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (request.getHeaders().contains(HttpHeader.AUTHORIZATION))
                {
                    Content.copy(request, response, callback);
                }
                else
                {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"%s\"".formatted(realm));
                    Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
                }
                return true;
            }
        });

        URI uri = newURI(transportType);
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "test", "secret"));

        String text1 = "hello";
        String text2 = "world";
        ReproducibleAsyncRequestContent body = new ReproducibleAsyncRequestContent();
        body.write(StandardCharsets.UTF_8.encode(text1));
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(
            client.newRequest(newURI(transportType))
                .method(HttpMethod.POST)
                .body(body)
        ).send();

        // The request was sent, wait for the server to reply with 401.
        Thread.sleep(1000);

        // Complete the request content.
        body.write(StandardCharsets.UTF_8.encode(text2));
        body.close();

        ContentResponse response = completable.get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(text1 + text2, response.getContentAsString());
    }

    private static class ReproducibleAsyncRequestContent implements org.eclipse.jetty.client.Request.Content, AutoCloseable
    {
        private static final ByteBuffer EOF = ByteBuffer.allocate(0);

        private final AutoLock lock = new AutoLock();
        private final List<ByteBuffer> chunks = new ArrayList<>();
        private Runnable demand;
        private int index;

        @Override
        public Content.Chunk read()
        {
            try (AutoLock ignored = lock.lock())
            {
                if (index == chunks.size())
                    return null;
                ByteBuffer byteBuffer = chunks.get(index);
                if (byteBuffer == EOF)
                    return Content.Chunk.EOF;
                ++index;
                return Content.Chunk.from(byteBuffer.slice(), false);
            }
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            boolean invoke;
            try (AutoLock ignored = lock.lock())
            {
                if (demand != null)
                    throw new IllegalStateException();
                invoke = index < chunks.size();
                if (!invoke)
                    demand = demandCallback;
            }
            if (invoke)
                invokeDemand(demandCallback);
        }

        private void invokeDemand(Runnable demandCallback)
        {
            demandCallback.run();
        }

        @Override
        public void fail(Throwable failure)
        {
            // Nothing to do in this simple implementation.
        }

        @Override
        public boolean rewind()
        {
            try (AutoLock ignored = lock.lock())
            {
                demand = null;
                index = 0;
            }
            return true;
        }

        public void write(ByteBuffer byteBuffer)
        {
            offer(byteBuffer);
        }

        @Override
        public void close()
        {
            offer(EOF);
        }

        private void offer(ByteBuffer byteBuffer)
        {
            Runnable demandCallback = null;
            try (AutoLock ignored = lock.lock())
            {
                if (index == chunks.size())
                {
                    demandCallback = demand;
                    demand = null;
                }
                chunks.add(byteBuffer);
            }
            if (demandCallback != null)
                invokeDemand(demandCallback);
        }
    }
}
