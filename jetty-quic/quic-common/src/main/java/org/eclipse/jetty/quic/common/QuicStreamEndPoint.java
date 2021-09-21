//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link EndPoint} implementation on top of a QUIC stream.</p>
 * <p>The correspondent {@link Connection} associated to this QuicStreamEndPoint
 * parses and generates the protocol specific bytes transported by QUIC.</p>
 */
public class QuicStreamEndPoint extends AbstractEndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStreamEndPoint.class);

    private final QuicSession session;
    private final long streamId;

    public QuicStreamEndPoint(Scheduler scheduler, QuicSession session, long streamId)
    {
        super(scheduler);
        this.session = session;
        this.streamId = streamId;
    }

    public QuicSession getQuicSession()
    {
        return session;
    }

    public long getStreamId()
    {
        return streamId;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return session.getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return session.getRemoteAddress();
    }

    public boolean isStreamFinished()
    {
        return session.isFinished(streamId);
    }

    @Override
    protected void doShutdownInput()
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("shutting down input of stream {}", streamId);
            session.shutdownInput(streamId);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down output of stream {}", streamId, x);
        }
    }

    @Override
    protected void doShutdownOutput()
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("shutting down output of stream {}", streamId);
            session.shutdownOutput(streamId);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down output of stream {}", streamId, x);
        }
    }

    @Override
    protected void doClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closing stream {}", streamId);
        doShutdownInput();
        doShutdownOutput();
    }

    @Override
    public void onClose(Throwable failure)
    {
        super.onClose(failure);
        session.onClose(streamId);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("filling buffer from stream {} finished={}", streamId, isStreamFinished());
        int pos = BufferUtil.flipToFill(buffer);
        int drained = session.fill(streamId, buffer);
        BufferUtil.flipToFlush(buffer, pos);
        return drained;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        // TODO: session.flush(streamId, buffer) feeds Quiche and then calls flush().
        //  Can we call flush() only after the for loop below?
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} buffer(s) to stream {}", buffers.length, streamId);
        for (ByteBuffer buffer : buffers)
        {
            int flushed = session.flush(streamId, buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} bytes to stream {}", flushed, streamId);
            if (buffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("incomplete flushing of stream {}", streamId);
                return false;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("flushed stream {}", streamId);
        return true;
    }

    public void write(Callback callback, List<ByteBuffer> buffers, boolean last)
    {
        // TODO: writing the last flag after the buffers is inefficient,
        //  but Quiche supports it, so we need to expose the Quiche API.
        write(Callback.from(callback.getInvocationType(), () -> finishWrite(callback, last), callback::failed), buffers.toArray(ByteBuffer[]::new));
    }

    private void finishWrite(Callback callback, boolean last)
    {
        try
        {
            if (last)
                session.flushFinished(streamId);
            callback.succeeded();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    @Override
    public Object getTransport()
    {
        return session;
    }

    public void onWritable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stream {} is writable", streamId);
        getWriteFlusher().completeWrite();
    }

    public boolean onReadable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stream {} is readable", streamId);
        getFillInterest().fillable();
        return isFillInterested();
    }

    @Override
    protected void onIncompleteFlush()
    {
        // No need to do anything.
        // See QuicSession.process().
    }

    @Override
    protected void needsFillInterest()
    {
        // No need to do anything.
        // See QuicSession.process().
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d[%s]->[%s]", getClass().getSimpleName(), hashCode(), getStreamId(), toEndPointString(), toConnectionString());
    }
}
