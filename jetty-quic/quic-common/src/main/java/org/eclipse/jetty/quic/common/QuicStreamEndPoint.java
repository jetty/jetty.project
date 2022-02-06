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

package org.eclipse.jetty.quic.common;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.io.WriteFlusher;
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
    private static final ByteBuffer LAST_FLAG = ByteBuffer.allocate(0);

    private final QuicSession session;
    private final long streamId;

    public QuicStreamEndPoint(Scheduler scheduler, QuicSession session, long streamId)
    {
        super(scheduler);
        this.session = session;
        this.streamId = streamId;
    }

    public void opened()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("opened {}", this);
        Connection connection = getConnection();
        if (connection != null)
            connection.onOpen();
    }

    public void closed(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closed {}", this);
        Connection connection = getConnection();
        if (connection != null)
            connection.onClose(failure);
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

    public void shutdownInput(long error)
    {
        try
        {
            shutdownInput();
            if (LOG.isDebugEnabled())
                LOG.debug("shutting down input with error 0x{} on {}", Long.toHexString(error), this);
            session.shutdownInput(streamId, error);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down input with error 0x{} on {}", Long.toHexString(error), this, x);
        }
    }

    public void shutdownOutput(long error)
    {
        try
        {
            shutdownOutput();
            if (LOG.isDebugEnabled())
                LOG.debug("shutting down output with error 0x{} on {}", Long.toHexString(error), this);
            session.shutdownOutput(streamId, error);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down output with error 0x{} on {}", Long.toHexString(error), this, x);
        }
    }

    public void close(long error, Throwable failure)
    {
        shutdownInput(error);
        FillInterest fillInterest = getFillInterest();
        if (failure == null)
            fillInterest.onClose();
        else
            fillInterest.onFail(failure);

        shutdownOutput(error);
        WriteFlusher writeFlusher = getWriteFlusher();
        if (failure == null)
            writeFlusher.onClose();
        else
            writeFlusher.onFail(failure);

        session.remove(this, failure);

        if (LOG.isDebugEnabled())
            LOG.debug("closed with error 0x{} {}", Long.toHexString(error), this, failure);
    }

    @Override
    public void onClose(Throwable failure)
    {
        // Implemented empty because we want to disable the standard
        // EndPoint close mechanism, since QUIC uses error codes.
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("filling buffer finished={} from {}", isStreamFinished(), this);
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

        int length = buffers.length;
        boolean last = buffers[length - 1] == LAST_FLAG;
        if (last)
            --length;
        if (LOG.isDebugEnabled())
            LOG.debug("flushing {} buffer(s) to {}", length, this);
        for (int i = 0; i < length; ++i)
        {
            ByteBuffer buffer = buffers[i];
            int flushed = session.flush(streamId, buffer, (i == length - 1) && last);
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} bytes window={}/{} to {}", flushed, session.getWindowCapacity(streamId), session.getWindowCapacity(), this);
            if (buffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("incomplete flushing of {}", this);
                return false;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("flushed {}", this);
        return true;
    }

    public void write(Callback callback, List<ByteBuffer> buffers, boolean last)
    {
        ByteBuffer[] array;
        if (last)
        {
            int size = buffers.size();
            array = new ByteBuffer[size + 1];
            IntStream.range(0, size).forEach(i -> array[i] = buffers.get(i));
            array[size] = LAST_FLAG;
        }
        else
        {
            array = buffers.toArray(ByteBuffer[]::new);
        }
        write(callback, array);
    }

    @Override
    public Object getTransport()
    {
        return session;
    }

    public void onWritable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stream #{} is writable", streamId);
        getWriteFlusher().completeWrite();
    }

    /**
     * @return whether this endPoint is interested in reads
     */
    public boolean onReadable()
    {
        // TODO: there is a race condition here.
        //  Thread T1 enters this method and sees that this endPoint
        //  is not interested, so it does not call onFillable().
        //  Thread T2 is in fillInterested() about to set interest.
        //  When this happens, this endPoint will miss a notification
        //  that there is data to read.
        //  The race condition is fixed by always calling produce()
        //  from fillInterested() methods below.

        // TODO: an alternative way of avoid the race would be to emulate an NIO style
        //  notification, where onReadable() gets called only if there is interest.
//        getQuicSession().setFillInterested(getStreamId(), false);

        boolean interested = isFillInterested();
        if (LOG.isDebugEnabled())
            LOG.debug("stream #{} is readable, processing: {}", streamId, interested);
        if (interested)
            getFillInterest().fillable();
        return interested;
    }

    @Override
    public void fillInterested(Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting fill interest on {}", this);
        super.fillInterested(callback);

        // TODO: see above
//        getQuicSession().setFillInterested(getStreamId(), true);

        // Method produce() could block, but it's called synchronously from the producer thread
        // which calls onReadable() above, so it will just go into re-producing and never block.
        getQuicSession().getProtocolSession().produce();
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("try setting fill interest on {}", this);
        boolean result = super.tryFillInterested(callback);
        getQuicSession().getProtocolSession().produce();
        return result;
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
