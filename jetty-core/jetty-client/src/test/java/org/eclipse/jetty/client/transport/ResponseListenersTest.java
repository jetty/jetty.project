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

package org.eclipse.jetty.client.transport;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.io.content.ChunksContentSource;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class ResponseListenersTest
{
    @Test
    public void testContentSourceDemultiplexerSpuriousWakeup()
    {
        TestSource contentSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        );

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();

        Request request = new TestRequest();

        ResponseListeners responseListeners = new ResponseListeners(request);
        Response.ContentSourceListener contentSourceListener = (r, source) ->
        {
            Runnable runnable = new Runnable()
            {
                @Override
                public void run()
                {
                    Content.Chunk chunk = source.read();
                    chunks.add(chunk);
                    if (chunk == null)
                    {
                        source.demand(this);
                        return;
                    }
                    if (!chunk.isLast())
                        source.demand(this);
                }
            };
            source.demand(runnable);
        };
        // Add 2 ContentSourceListeners to enable the use of ContentSourceDemultiplexer.
        responseListeners.addContentSourceListener(contentSourceListener);
        responseListeners.addContentSourceListener(contentSourceListener);

        responseListeners.notifyContentSource(new TestResponse(), contentSource);

        assertThat("Chunks: " + chunks, chunks.size(), is(6));
        assertThat(chunks.get(0).isLast(), is(false));
        assertThat(chunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(1).isLast(), is(false));
        assertThat(chunks.get(1).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(2).isLast(), is(false));
        assertThat(chunks.get(2).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(3).isLast(), is(false));
        assertThat(chunks.get(3).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(4).isLast(), is(true));
        assertThat(chunks.get(4).getByteBuffer().get(), is((byte)3));
        assertThat(chunks.get(5).isLast(), is(true));
        assertThat(chunks.get(5).getByteBuffer().get(), is((byte)3));

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }

    @Test
    public void testContentSourceDemultiplexerFailOnTransientException()
    {
        TestSource contentSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(new TimeoutException("timeout"), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        );

        Request request = new TestRequest();

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();
        ResponseListeners responseListeners = new ResponseListeners(request);
        Response.ContentSourceListener contentSourceListener = (r, source) ->
        {
            Runnable runnable = new Runnable()
            {
                @Override
                public void run()
                {
                    Content.Chunk chunk = source.read();
                    chunks.add(chunk);
                    if (chunk == null)
                    {
                        source.demand(this);
                        return;
                    }
                    if (Content.Chunk.isFailure(chunk, false))
                        source.fail(new NumberFormatException());
                    if (!chunk.isLast())
                        source.demand(this);
                }
            };
            source.demand(runnable);
        };
        // Add 2 ContentSourceListeners to enable the use of ContentSourceDemultiplexer.
        responseListeners.addContentSourceListener(contentSourceListener);
        responseListeners.addContentSourceListener(contentSourceListener);

        responseListeners.notifyContentSource(new TestResponse(), contentSource);

        assertThat(chunks.size(), is(8));
        assertThat(chunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(0).isLast(), is(false));
        assertThat(chunks.get(1).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(1).isLast(), is(false));
        assertThat(chunks.get(2).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(2).isLast(), is(false));
        assertThat(chunks.get(3).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(3).isLast(), is(false));

        // Failures are not alternated because ContentSourceDemultiplexer is failed,
        // it immediately services demands.
        assertThat(Content.Chunk.isFailure(chunks.get(4), false), is(true));
        assertThat(chunks.get(4).getFailure(), instanceOf(TimeoutException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(5), true), is(true));
        assertThat(chunks.get(5).getFailure(), instanceOf(NumberFormatException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(6), false), is(true));
        assertThat(chunks.get(6).getFailure(), instanceOf(TimeoutException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(7), true), is(true));
        assertThat(chunks.get(7).getFailure(), instanceOf(NumberFormatException.class));

        Content.Chunk chunk = contentSource.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }

    @Test
    public void testContentSourceDemultiplexerFailOnTerminalException()
    {
        TestSource contentSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(new ArithmeticException(), true)
        );

        Request request = new TestRequest();

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();
        ResponseListeners responseListeners = new ResponseListeners(request);
        Response.ContentSourceListener contentSourceListener = (r, source) ->
        {
            Runnable runnable = new Runnable()
            {
                @Override
                public void run()
                {
                    Content.Chunk chunk = source.read();
                    chunks.add(chunk);
                    if (chunk == null)
                    {
                        source.demand(this);
                        return;
                    }
                    if (Content.Chunk.isFailure(chunk))
                        source.fail(new NumberFormatException());
                    if (!chunk.isLast())
                        source.demand(this);
                }
            };
            source.demand(runnable);
        };
        // Add 2 ContentSourceListeners to enable the use of ContentSourceDemultiplexer.
        responseListeners.addContentSourceListener(contentSourceListener);
        responseListeners.addContentSourceListener(contentSourceListener);

        responseListeners.notifyContentSource(new TestResponse(), contentSource);

        assertThat(chunks.size(), is(6));
        assertThat(chunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(0).isLast(), is(false));
        assertThat(chunks.get(1).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(1).isLast(), is(false));
        assertThat(chunks.get(2).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(2).isLast(), is(false));
        assertThat(chunks.get(3).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(3).isLast(), is(false));
        assertThat(Content.Chunk.isFailure(chunks.get(4), true), is(true));
        assertThat(chunks.get(4).getFailure(), instanceOf(ArithmeticException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(5), true), is(true));
        assertThat(chunks.get(5).getFailure(), instanceOf(ArithmeticException.class));

        Content.Chunk chunk = contentSource.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(ArithmeticException.class));
        assertThat(chunk.getFailure().getSuppressed().length, is(2));
        assertThat(chunk.getFailure().getSuppressed()[0], instanceOf(NumberFormatException.class));
        assertThat(chunk.getFailure().getSuppressed()[1], instanceOf(NumberFormatException.class));

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }

    @Test
    public void testEmitEventsInvokesContentSourceListenerForNoContent()
    {
        TestRequest request = new TestRequest();
        ResponseListeners responseListeners = new ResponseListeners(request);
        List<String> events = new ArrayList<>();
        responseListeners.addListener(new Response.Listener()
        {
            @Override
            public void onBegin(Response response)
            {
                events.add("BEGIN");
            }

            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                return events.add("HEADER");
            }

            @Override
            public void onHeaders(Response response)
            {
                events.add("HEADERS");
            }

            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                events.add("CONTENT-SOURCE");
            }

            @Override
            public void onSuccess(Response response)
            {
                events.add("SUCCESS");
            }
        });

        Response response = new HttpResponse(request).addHeader(HttpFields.CONTENT_LENGTH_0);
        Response contentResponse = new HttpContentResponse(response, BufferUtil.EMPTY_BYTES, null, null);
        responseListeners.emitSuccess(contentResponse);

        List<String> expected = List.of("BEGIN", "HEADER", "HEADERS", "CONTENT-SOURCE", "SUCCESS");
        assertThat(events, is(expected));
    }

    private static class TestSource extends ChunksContentSource implements Closeable
    {
        private Content.Chunk[] chunks;

        public TestSource(Content.Chunk... chunks)
        {
            super(Arrays.asList(chunks));
            this.chunks = chunks;
        }

        @Override
        public void close()
        {
            if (chunks != null)
            {
                for (Content.Chunk chunk : chunks)
                {
                    if (chunk != null)
                        chunk.release();
                }
                chunks = null;
            }
        }
    }

    private static class TestRequest implements Request
    {
        @Override
        public Connection getConnection()
        {
            return null;
        }

        @Override
        public String getScheme()
        {
            return "";
        }

        @Override
        public Request scheme(String scheme)
        {
            return null;
        }

        @Override
        public String getHost()
        {
            return "";
        }

        @Override
        public Request host(String host)
        {
            return null;
        }

        @Override
        public int getPort()
        {
            return 0;
        }

        @Override
        public Request port(int port)
        {
            return null;
        }

        @Override
        public Transport getTransport()
        {
            return null;
        }

        @Override
        public Request transport(Transport transport)
        {
            return null;
        }

        @Override
        public String getMethod()
        {
            return "";
        }

        @Override
        public Request method(HttpMethod method)
        {
            return null;
        }

        @Override
        public Request method(String method)
        {
            return null;
        }

        @Override
        public String getPath()
        {
            return "";
        }

        @Override
        public Request path(String path)
        {
            return null;
        }

        @Override
        public String getQuery()
        {
            return "";
        }

        @Override
        public URI getURI()
        {
            return null;
        }

        @Override
        public HttpVersion getVersion()
        {
            return null;
        }

        @Override
        public Request version(HttpVersion version)
        {
            return null;
        }

        @Override
        public Fields getParams()
        {
            return null;
        }

        @Override
        public Request param(String name, String value)
        {
            return null;
        }

        @Override
        public HttpFields getHeaders()
        {
            return null;
        }

        @Override
        public Request headers(Consumer<HttpFields.Mutable> consumer)
        {
            return null;
        }

        @Override
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return null;
        }

        @Override
        public Request trailersSupplier(Supplier<HttpFields> trailers)
        {
            return null;
        }

        @Override
        public List<HttpCookie> getCookies()
        {
            return List.of();
        }

        @Override
        public Request cookie(HttpCookie cookie)
        {
            return null;
        }

        @Override
        public Object getTag()
        {
            return null;
        }

        @Override
        public Request tag(Object tag)
        {
            return null;
        }

        @Override
        public Map<String, Object> getAttributes()
        {
            return Map.of();
        }

        @Override
        public Request attribute(String name, Object value)
        {
            return null;
        }

        @Override
        public Content getBody()
        {
            return null;
        }

        @Override
        public Request body(Content content)
        {
            return null;
        }

        @Override
        public Request file(Path file) throws IOException
        {
            return null;
        }

        @Override
        public Request file(Path file, String contentType) throws IOException
        {
            return null;
        }

        @Override
        public String getAgent()
        {
            return "";
        }

        @Override
        public Request agent(String agent)
        {
            return null;
        }

        @Override
        public Request accept(String... accepts)
        {
            return null;
        }

        @Override
        public long getIdleTimeout()
        {
            return 0;
        }

        @Override
        public Request idleTimeout(long timeout, TimeUnit unit)
        {
            return null;
        }

        @Override
        public long getTimeout()
        {
            return 0;
        }

        @Override
        public Request timeout(long timeout, TimeUnit unit)
        {
            return null;
        }

        @Override
        public boolean isFollowRedirects()
        {
            return false;
        }

        @Override
        public Request followRedirects(boolean follow)
        {
            return null;
        }

        @Override
        public Request onRequestListener(Listener listener)
        {
            return null;
        }

        @Override
        public Request onRequestQueued(QueuedListener listener)
        {
            return null;
        }

        @Override
        public Request onRequestBegin(BeginListener listener)
        {
            return null;
        }

        @Override
        public Request onRequestHeaders(HeadersListener listener)
        {
            return null;
        }

        @Override
        public Request onRequestCommit(CommitListener listener)
        {
            return null;
        }

        @Override
        public Request onRequestContent(ContentListener listener)
        {
            return null;
        }

        @Override
        public Request onRequestSuccess(SuccessListener listener)
        {
            return null;
        }

        @Override
        public Request onRequestFailure(FailureListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseListener(Response.Listener listener)
        {
            return null;
        }

        @Override
        public Request onResponseBegin(Response.BeginListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseHeader(Response.HeaderListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseHeaders(Response.HeadersListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseContent(Response.ContentListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseContentAsync(Response.AsyncContentListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseContentSource(Response.ContentSourceListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseSuccess(Response.SuccessListener listener)
        {
            return null;
        }

        @Override
        public Request onResponseFailure(Response.FailureListener listener)
        {
            return null;
        }

        @Override
        public Request onPush(BiFunction<Request, Request, Response.CompleteListener> pushHandler)
        {
            return null;
        }

        @Override
        public Request onComplete(Response.CompleteListener listener)
        {
            return null;
        }

        @Override
        public ContentResponse send() throws InterruptedException, TimeoutException, ExecutionException
        {
            return null;
        }

        @Override
        public void send(Response.CompleteListener listener)
        {

        }

        @Override
        public CompletableFuture<Boolean> abort(Throwable cause)
        {
            return null;
        }

        @Override
        public Throwable getAbortCause()
        {
            return null;
        }
    }

    private static class TestResponse implements Response
    {
        @Override
        public Request getRequest()
        {
            return null;
        }

        @Override
        public HttpVersion getVersion()
        {
            return null;
        }

        @Override
        public int getStatus()
        {
            return 0;
        }

        @Override
        public String getReason()
        {
            return "";
        }

        @Override
        public HttpFields getHeaders()
        {
            return null;
        }

        @Override
        public HttpFields getTrailers()
        {
            return null;
        }

        @Override
        public CompletableFuture<Boolean> abort(Throwable cause)
        {
            return null;
        }
    }
}
