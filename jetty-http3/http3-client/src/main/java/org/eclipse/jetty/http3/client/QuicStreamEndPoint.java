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

package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return session.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return session.getRemoteAddress();
    }

    @Override
    protected void doShutdownInput()
    {
        try
        {
            session.shutdownInput(streamId);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down output", x);
        }
    }

    @Override
    protected void doShutdownOutput()
    {
        try
        {
            session.shutdownOutput(streamId);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error shutting down output", x);
        }
    }

    @Override
    public void onClose(Throwable failure)
    {
        try
        {
            session.flushFinished(streamId);
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error sending FIN on stream {}", streamId, e);
        }
        super.onClose(failure);
        session.onClose(streamId);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int pos = BufferUtil.flipToFill(buffer);
        int drained = session.fill(streamId, buffer);
        BufferUtil.flipToFlush(buffer, pos);
        if (session.isFinished(streamId))
            shutdownInput();
        return drained;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        for (ByteBuffer buffer : buffers)
        {
            int flushed = session.flush(streamId, buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} bytes to stream {}; buffer has remaining? {}", flushed, streamId, buffer.hasRemaining());
            if (buffer.hasRemaining())
                return false;
        }
        return true;
    }

    @Override
    public Object getTransport()
    {
        return session;
    }

    public void onWritable()
    {
        getWriteFlusher().completeWrite();
    }

    public void onReadable()
    {
        getFillInterest().fillable();
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
}
