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
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class MockHttpStream implements HttpStream
{
    private static final Throwable SUCCEEDED = new Throwable();
    private static final Content DEMAND = new Content.Abstract(true, false) {};
    private final long nano = System.nanoTime();
    private final AtomicReference<Content> _content = new AtomicReference<>();
    private final AtomicReference<Throwable> _complete = new AtomicReference<>();
    private final CountDownLatch _completed = new CountDownLatch(1);
    private final ByteBufferAccumulator _accumulator = new ByteBufferAccumulator();
    private final AtomicReference<ByteBuffer> _out = new AtomicReference<>();
    private final HttpChannelState _channel;
    private final AtomicReference<MetaData.Response> _response = new AtomicReference<>();
    private final HttpFields.Mutable _responseHeaders = HttpFields.build();
    private HttpFields.Mutable _responseTrailers;

    public MockHttpStream(HttpChannelState channel)
    {
        this(channel, true);
    }

    public MockHttpStream(HttpChannelState channel, boolean atEof)
    {
        channel.setHttpStream(this);
        _channel = channel;
        if (atEof)
            _content.set(Content.EOF);
    }

    public boolean isDemanding()
    {
        return _content.get() == DEMAND;
    }

    public Runnable addContent(ByteBuffer buffer, boolean last)
    {
        return addContent((last && BufferUtil.isEmpty(buffer)) ? Content.EOF : Content.from(buffer, last));
    }

    public Runnable addContent(String content, boolean last)
    {
        return addContent(BufferUtil.toBuffer(content), last);
    }

    public Runnable addContent(Content content)
    {
        content = _content.getAndSet(content);
        if (content == DEMAND)
            return _channel.onContentAvailable();
        else if (content != null)
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
    public long getNanoTimeStamp()
    {
        return nano;
    }

    @Override
    public Content readContent()
    {
        Content content = _content.get();
        if (content == null || content == DEMAND)
            return null;

        _content.set(Content.next(content));

        return content;
    }

    @Override
    public void demandContent()
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
    public void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
    {
        if (response != null)
        {
            MetaData.Response r = _response.getAndSet(response);

            _responseHeaders.add(response.getFields());

            if (r != null && r.getStatus() >= 200)
            {
                callback.failed(new IOException("already committed"));
                return;
            }

            if (response.getFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()) &&
                _channel.getConnectionMetaData() instanceof MockConnectionMetaData)
                ((MockConnectionMetaData)_channel.getConnectionMetaData()).notPersistent();
        }

        for (ByteBuffer buffer : content)
            _accumulator.copyBuffer(buffer);

        if (last)
        {
            Supplier<HttpFields> trailersSupplier = _response.get().getTrailerSupplier();
            if (trailersSupplier != null)
                _responseTrailers = HttpFields.build(trailersSupplier.get());

            if (!_out.compareAndSet(null, _accumulator.takeByteBuffer()))
            {
                if (response != null || content.length > 0)
                {
                    callback.failed(new IOException("EOF"));
                    return;
                }
            }
        }
        callback.succeeded();
    }

    @Override
    public boolean isPushSupported()
    {
        return false;
    }

    @Override
    public void push(MetaData.Request request)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCommitted()
    {
        MetaData.Response response = _response.get();
        return response != null && response.getStatus() >= 200;
    }

    @Override
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
        if (_complete.compareAndSet(null, SUCCEEDED))
            _completed.countDown();
    }

    @Override
    public void failed(Throwable x)
    {
        if (_channel.getConnectionMetaData() instanceof MockConnectionMetaData)
            ((MockConnectionMetaData)_channel.getConnectionMetaData()).notPersistent();
        if (_complete.compareAndSet(null, x == null ? new Throwable() : x))
            _completed.countDown();
    }

    @Override
    public void setUpgradeConnection(Connection connection)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Connection upgrade()
    {
        throw new UnsupportedOperationException();
    }
}
