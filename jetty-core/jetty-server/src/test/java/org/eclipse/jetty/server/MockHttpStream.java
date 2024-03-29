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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class MockHttpStream implements HttpStream
{
    private static final Throwable SUCCEEDED = new Throwable();
    private static final Content.Chunk DEMAND = new Content.Chunk()
    {
        @Override
        public ByteBuffer getByteBuffer()
        {
            return BufferUtil.EMPTY_BUFFER;
        }

        @Override
        public boolean isLast()
        {
            return false;
        }
    };
    private final AtomicReference<Content.Chunk> _content = new AtomicReference<>();
    private final AtomicReference<Throwable> _complete = new AtomicReference<>();
    private final CountDownLatch _completed = new CountDownLatch(1);
    private final ByteBufferAccumulator _accumulator = new ByteBufferAccumulator();
    private final AtomicReference<ByteBuffer> _out = new AtomicReference<>();
    private final HttpChannel _channel;
    private final AtomicReference<MetaData.Response> _response = new AtomicReference<>();
    private final HttpFields.Mutable _responseHeaders = HttpFields.build();
    private HttpFields.Mutable _responseTrailers;

    public MockHttpStream(HttpChannel channel)
    {
        this(channel, true);
    }

    public MockHttpStream(HttpChannel channel, boolean atEof)
    {
        _channel = channel;
        channel.setHttpStream(this);
        if (atEof)
            _content.set(Content.Chunk.EOF);
    }

    public boolean isDemanding()
    {
        return _content.get() == DEMAND;
    }

    public Runnable addContent(ByteBuffer buffer, boolean last)
    {
        return addContent(Content.Chunk.from(buffer, last));
    }

    public Runnable addContent(String content, boolean last)
    {
        return addContent(BufferUtil.toBuffer(content), last);
    }

    private Runnable addContent(Content.Chunk chunk)
    {
        chunk.retain();
        chunk = _content.getAndSet(chunk);
        if (chunk == DEMAND)
            return _channel.onContentAvailable();
        else if (chunk != null)
            throw new IllegalStateException();
        return null;
    }

    public MetaData.Response getResponse()
    {
        return _response.get();
    }

    public HttpFields getResponseHeaders()
    {
        return _responseHeaders;
    }

    public HttpFields getResponseTrailers()
    {
        return _responseTrailers;
    }

    public ByteBuffer getResponseContent()
    {
        return _out.get();
    }

    public String getResponseContentAsString()
    {
        ByteBuffer buffer = _out.get();
        if (buffer == null)
            return null;
        if (buffer.remaining() == 0)
            return "";
        return BufferUtil.toString(buffer);
    }

    public Throwable getFailure()
    {
        Throwable t = _complete.get();
        return t == SUCCEEDED ? null : t;
    }

    @Override
    public String getId()
    {
        return "teststream";
    }

    @Override
    public Content.Chunk read()
    {
        Content.Chunk chunk = _content.get();
        if (chunk == null || chunk == DEMAND)
            return null;

        _content.set(Content.Chunk.next(chunk));

        return chunk;
    }

    @Override
    public void demand()
    {
        if (!_content.compareAndSet(null, DEMAND))
        {
            Runnable todo = _channel.onContentAvailable();
            if (todo != null)
                todo.run();
        }
    }

    @Override
    public void prepareResponse(HttpFields.Mutable headers)
    {
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
    {
        if (response != null)
        {
            MetaData.Response r = _response.getAndSet(response);

            _responseHeaders.add(response.getHttpFields());

            if (r != null && r.getStatus() >= 200)
            {
                callback.failed(new IOException("already committed"));
                return;
            }

            if (response.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()) &&
                _channel.getConnectionMetaData() instanceof MockConnectionMetaData mock)
                mock.notPersistent();
        }

        if (content != null)
            _accumulator.copyBuffer(content);

        if (last)
        {
            Supplier<HttpFields> trailersSupplier = _response.get().getTrailersSupplier();
            if (trailersSupplier != null)
            {
                HttpFields trailers = trailersSupplier.get();
                if (trailers != null)
                    _responseTrailers = HttpFields.build(trailers);
            }

            if (!_out.compareAndSet(null, _accumulator.takeRetainableByteBuffer().getByteBuffer()))
            {
                if (response != null || content != null)
                {
                    callback.failed(new IOException("EOF"));
                    return;
                }
            }
        }
        callback.succeeded();
    }

    @Override
    public long getIdleTimeout()
    {
        return 0;
    }

    @Override
    public void setIdleTimeout(long idleTimeoutMs)
    {
    }

    @Override
    public boolean isCommitted()
    {
        MetaData.Response response = _response.get();
        return response != null && response.getStatus() >= 200;
    }

    @Override
    public Throwable consumeAvailable()
    {
        return HttpStream.consumeAvailable(this, new HttpConfiguration());
    }

    public boolean isComplete()
    {
        return _completed.getCount() == 0;
    }

    public boolean waitForComplete(long timeout, TimeUnit units)
    {
        try
        {
            return _completed.await(timeout, units);
        }
        catch (InterruptedException e)
        {
            return false;
        }
    }

    @Override
    public void succeeded()
    {
        _channel.recycle();
        if (_complete.compareAndSet(null, SUCCEEDED))
            _completed.countDown();
    }

    @Override
    public void failed(Throwable x)
    {
        if (_channel.getConnectionMetaData() instanceof MockConnectionMetaData mock)
            mock.notPersistent();
        if (_complete.compareAndSet(null, x == null ? new Throwable() : x))
            _completed.countDown();
    }
}
