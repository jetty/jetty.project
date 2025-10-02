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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.internal.Transferable;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link EndPoint} implementation based on {@link SocketChannel}.</p>
 */
public class SocketChannelEndPoint extends SelectableChannelEndPoint implements Transferable.To
{
    private static final Logger LOG = LoggerFactory.getLogger(SocketChannelEndPoint.class);

    private final AtomicReference<Callback> transferCallback = new AtomicReference<>();

    public SocketChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        super(scheduler, channel, selector, key);
    }

    @Override
    public SocketChannel getChannel()
    {
        return (SocketChannel)super.getChannel();
    }

    @Override
    public boolean transferFrom(FileChannel fileChannel, long offset, long length, Callback callback)
    {
        Transferable.transfer(fileChannel, offset, length, this, callback);
        return true;
    }

    public void onIncompleteTransfer(Callback callback)
    {
        if (transferCallback.compareAndSet(null, callback))
            onIncompleteFlush();
        else
            throw new IllegalStateException("Transfer callback already present");
    }

    @Override
    protected Runnable taskForSelected(boolean fillable, boolean flushable)
    {
        Callback callback = transferCallback.getAndSet(null);
        if (callback == null)
            return super.taskForSelected(fillable, flushable);

        // For the transfer case, only flushable must be true.
        assert !fillable && flushable;

        return new Invocable.ReadyTask(callback.getInvocationType(), callback::succeeded);
    }

    @Override
    public void onClose(Throwable cause)
    {
        Callback callback = transferCallback.getAndSet(null);
        if (callback != null)
            callback.failed(cause == null ? new ClosedChannelException() : cause);
        super.onClose(cause);
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {
        Callback callback = transferCallback.getAndSet(null);
        if (callback != null)
            callback.failed(timeout);
        super.onIdleExpired(timeout);
    }

    // TODO: override onFail()

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        try
        {
            return getChannel().getRemoteAddress();
        }
        catch (Throwable x)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("Could not retrieve remote socket address", x);
            return null;
        }
    }

    @Override
    protected void doShutdownOutput()
    {
        try
        {
            getChannel().shutdownOutput();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("Could not shutdown output for {}", getChannel());
        }
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (isInputShutdown())
            return -1;

        int pos = BufferUtil.flipToFill(buffer);
        int filled;
        try
        {
            filled = getChannel().read(buffer);
            if (filled > 0)
                notIdle();
            else if (filled == -1)
                shutdownInput();
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to shutdown input", e);
            shutdownInput();
            filled = -1;
        }
        finally
        {
            BufferUtil.flipToFlush(buffer, pos);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("filled {} {}", filled, BufferUtil.toDetailString(buffer));
        return filled;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        long flushed;
        try
        {
            flushed = getChannel().write(buffers);
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} {}", flushed, this);
        }
        catch (IOException e)
        {
            throw new EofException(e);
        }

        if (flushed > 0)
            notIdle();

        for (ByteBuffer b : buffers)
        {
            if (!BufferUtil.isEmpty(b))
                return false;
        }

        return true;
    }
}
