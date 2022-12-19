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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
 * Context#run(Runnable)} to setup classloaders etc. </p>
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpInput.class);

    /**
     * The lock shared with {@link AsyncContentProducer} and this class.
     */
    final AutoLock _lock = new AutoLock();
    private final ServletChannel _servletChannel;
    private final ServletRequestState _channelState;
    private final byte[] _oneByteBuffer = new byte[1];
    private final BlockingContentProducer _blockingContentProducer;
    private final AsyncContentProducer _asyncContentProducer;
    private final LongAdder _contentConsumed = new LongAdder();
    private volatile ContentProducer _contentProducer;
    private volatile boolean _consumedEof;
    private volatile ReadListener _readListener;

    public HttpInput(ServletChannel channel)
    {
        _servletChannel = channel;
        _channelState = _servletChannel.getState();
        _asyncContentProducer = new AsyncContentProducer(_servletChannel, _lock);
        _blockingContentProducer = new BlockingContentProducer(_asyncContentProducer);
        _contentProducer = _blockingContentProducer;
    }

    public void recycle()
    {
        try (AutoLock lock = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycle {}", this);
            _blockingContentProducer.recycle();
            _contentProducer = _blockingContentProducer;
        }
    }

    public void reopen()
    {
        try (AutoLock lock = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("reopen {}", this);
            _blockingContentProducer.reopen();
            _contentProducer = _blockingContentProducer;
            _consumedEof = false;
            _readListener = null;
            _contentConsumed.reset();
        }
    }

    private int get(Content.Chunk chunk, byte[] bytes, int offset, int length)
    {
        length = Math.min(chunk.remaining(), length);
        chunk.getByteBuffer().get(bytes, offset, length);
        _contentConsumed.add(length);
        return length;
    }

    private int get(Content.Chunk chunk, ByteBuffer des)
    {
        var capacity = des.remaining();
        var src = chunk.getByteBuffer();
        if (src.remaining() > capacity)
        {
            int limit = src.limit();
            src.limit(src.position() + capacity);
            des.put(src);
            src.limit(limit);
        }
        else
        {
            des.put(src);
        }
        var consumed = capacity - des.remaining();
        _contentConsumed.add(consumed);
        return consumed;
    }

    public long getContentConsumed()
    {
        return _contentConsumed.sum();
    }

    public long getContentReceived()
    {
        try (AutoLock lock = _lock.lock())
        {
            return _contentProducer.getBytesArrived();
        }
    }

    public boolean consumeAvailable()
    {
        try (AutoLock lock = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("consumeAll {}", this);
            boolean atEof = _contentProducer.consumeAvailable();
            if (atEof)
                _consumedEof = true;

            if (isFinished())
                return !isError();

            return false;
        }
    }

    public boolean isError()
    {
        try (AutoLock lock = _lock.lock())
        {
            boolean error = _contentProducer.isError();
            if (LOG.isDebugEnabled())
                LOG.debug("isError={} {}", error, this);
            return error;
        }
    }

    public boolean isAsync()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("isAsync read listener {} {}", _readListener, this);
        return _readListener != null;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        boolean finished = _consumedEof;
        if (LOG.isDebugEnabled())
            LOG.debug("isFinished={} {}", finished, this);
        return finished;
    }

    @Override
    public boolean isReady()
    {
        try (AutoLock lock = _lock.lock())
        {
            boolean ready = _contentProducer.isReady();
            if (LOG.isDebugEnabled())
                LOG.debug("isReady={} {}", ready, this);
            return ready;
        }
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting read listener to {} {}", readListener, this);
        if (_readListener != null)
            throw new IllegalStateException("ReadListener already set");
        //illegal if async not started
        if (!_channelState.isAsyncStarted())
            throw new IllegalStateException("Async not started");
        _readListener = Objects.requireNonNull(readListener);

        _contentProducer = _asyncContentProducer;
        // trigger content production
        if (isReady() && _channelState.onReadEof()) // onReadEof b/c we want to transition from WAITING to WOKEN
            scheduleReadListenerNotification(); // this is needed by AsyncServletIOTest.testStolenAsyncRead
    }

    public boolean onContentProducible()
    {
        try (AutoLock lock = _lock.lock())
        {
            return _contentProducer.onContentProducible();
        }
    }

    @Override
    public int read() throws IOException
    {
        try (AutoLock lock = _lock.lock())
        {
            int read = read(_oneByteBuffer, 0, 1);
            if (read == 0)
                throw new IOException("unready read=0");
            return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        return read(null, b, off, len);
    }

    public int read(ByteBuffer buffer) throws IOException
    {
        return read(buffer, null, -1, -1);
    }

    private int read(ByteBuffer buffer, byte[] b, int off, int len) throws IOException
    {
        try (AutoLock lock = _lock.lock())
        {
            // Don't try to get content if no bytes were requested to be read.
            if (len == 0)
                return 0;

            // Calculate minimum request rate for DoS protection
            _contentProducer.checkMinDataRate();

            Content.Chunk chunk = _contentProducer.nextChunk();
            if (chunk == null)
                throw new IllegalStateException("read on unready input");
            if (chunk.hasRemaining())
            {
                int read = buffer == null ? get(chunk, b, off, len) : get(chunk, buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("read produced {} byte(s) {}", read, this);
                if (!chunk.hasRemaining())
                    _contentProducer.reclaim(chunk);
                return read;
            }

            if (chunk instanceof Content.Chunk.Error errorChunk)
            {
                Throwable error = errorChunk.getCause();
                if (LOG.isDebugEnabled())
                    LOG.debug("read error={} {}", error, this);
                if (error instanceof IOException)
                    throw (IOException)error;
                throw new IOException(error);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("read at EOF, setting consumed EOF to true {}", this);
            _consumedEof = true;
            // If EOF do we need to wake for allDataRead callback?
            if (onContentProducible())
                scheduleReadListenerNotification();
            return -1;
        }
    }

    private void scheduleReadListenerNotification()
    {
        _servletChannel.execute(_servletChannel::handle);
    }

    /**
     * Check if this HttpInput instance has content stored internally, without fetching/parsing
     * anything from the underlying channel.
     * @return true if the input contains content, false otherwise.
     */
    public boolean hasContent()
    {
        try (AutoLock lock = _lock.lock())
        {
            // Do not call _contentProducer.available() as it calls HttpChannel.produceContent()
            // which is forbidden by this method's contract.
            boolean hasContent = _contentProducer.hasChunk();
            if (LOG.isDebugEnabled())
                LOG.debug("hasContent={} {}", hasContent, this);
            return hasContent;
        }
    }

    @Override
    public int available()
    {
        try (AutoLock lock = _lock.lock())
        {
            int available = _contentProducer.available();
            if (LOG.isDebugEnabled())
                LOG.debug("available={} {}", available, this);
            return available;
        }
    }

    /* Runnable */

    /*
     * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        Content.Chunk chunk;
        try (AutoLock lock = _lock.lock())
        {
            // Call isReady() to make sure that if not ready we register for fill interest.
            if (!_contentProducer.isReady())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running but not ready {}", this);
                return;
            }
            chunk = _contentProducer.nextChunk();
            if (LOG.isDebugEnabled())
                LOG.debug("running on content {} {}", chunk, this);
        }

        // This check is needed when a request is started async but no read listener is registered.
        if (_readListener == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running without a read listener {}", this);
            onContentProducible();
            return;
        }

        if (chunk instanceof Content.Chunk.Error errorChunk)
        {
            Throwable error = errorChunk.getCause();
            if (LOG.isDebugEnabled())
                LOG.debug("running error={} {}", error, this);
            // TODO is this necessary to add here?
            _servletChannel.getResponse().getHeaders().add(HttpFields.CONNECTION_CLOSE);
            _readListener.onError(error);
        }
        else if (chunk.isLast() && !chunk.hasRemaining())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running at EOF {}", this);
                _readListener.onAllDataRead();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running failed onAllDataRead {}", this, x);
                _readListener.onError(x);
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has content {}", this);
            try
            {
                _readListener.onDataAvailable();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running failed onDataAvailable {}", this, x);
                _readListener.onError(x);
            }
        }
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "@" + hashCode() +
            " cs=" + _channelState +
            " cp=" + _contentProducer +
            " eof=" + _consumedEof;
    }
}
