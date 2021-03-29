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

package org.eclipse.jetty.http3.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Objects;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicDatagramEndPoint extends AbstractEndPoint implements ManagedSelector.Selectable
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicDatagramEndPoint.class);

    /**
     * {@link #fill(ByteBuffer)} needs to pass the {@link InetSocketAddress} together with the buffer
     * and {@link #flush(ByteBuffer...)} needs the {@link InetSocketAddress} passed together with the buffer.
     * Since we cannot change the {@link org.eclipse.jetty.io.EndPoint} API, the {@link InetSocketAddress}
     * argument must be passed on the side with this thread-local.
     *
     * Note: a first implementation was encoding the InetSocketAddress in the buffer(s) but this was as complex
     * and required a mildly expensive encode-decode cycle each time one of those two methods was called.
     * This mechanism is as complex and brittle but virtually as cheap as standard argument passing.
     */
    public static InetAddressArgument INET_ADDRESS_ARGUMENT = new InetAddressArgument();

    private final AutoLock _lock = new AutoLock();
    private final DatagramChannel _channel;
    private final ManagedSelector _selector;
    private SelectionKey _key;
    private boolean _updatePending;
    // The current value for interestOps.
    private int _currentInterestOps;
    // The desired value for interestOps.
    private int _desiredInterestOps;

    private abstract class RunnableTask implements Runnable, Invocable
    {
        final String _operation;

        protected RunnableTask(String op)
        {
            _operation = op;
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s:%s", QuicDatagramEndPoint.this, _operation, getInvocationType());
        }
    }

    private abstract class RunnableCloseable extends RunnableTask implements Closeable
    {
        protected RunnableCloseable(String op)
        {
            super(op);
        }

        @Override
        public void close()
        {
            try
            {
                QuicDatagramEndPoint.this.close();
            }
            catch (Throwable x)
            {
                LOG.warn("Unable to close {}", QuicDatagramEndPoint.this, x);
            }
        }
    }

    private final ManagedSelector.SelectorUpdate _updateKeyAction = this::updateKeyAction;

    private final Runnable _runFillable = new RunnableCloseable("runFillable")
    {
        @Override
        public InvocationType getInvocationType()
        {
            return getFillInterest().getCallbackInvocationType();
        }

        @Override
        public void run()
        {
            getFillInterest().fillable();
        }
    };

    private final Runnable _runCompleteWrite = new RunnableCloseable("runCompleteWrite")
    {
        @Override
        public InvocationType getInvocationType()
        {
            return getWriteFlusher().getCallbackInvocationType();
        }

        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s:%s->%s", QuicDatagramEndPoint.this, _operation, getInvocationType(), getWriteFlusher());
        }
    };

    private final Runnable _runCompleteWriteFillable = new RunnableCloseable("runCompleteWriteFillable")
    {
        @Override
        public InvocationType getInvocationType()
        {
            InvocationType fillT = getFillInterest().getCallbackInvocationType();
            InvocationType flushT = getWriteFlusher().getCallbackInvocationType();
            if (fillT == flushT)
                return fillT;

            if (fillT == InvocationType.EITHER && flushT == InvocationType.NON_BLOCKING)
                return InvocationType.EITHER;

            if (fillT == InvocationType.NON_BLOCKING && flushT == InvocationType.EITHER)
                return InvocationType.EITHER;

            return InvocationType.BLOCKING;
        }

        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
            getFillInterest().fillable();
        }
    };

    public QuicDatagramEndPoint(DatagramChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        super(scheduler);
        _channel = channel;
        _selector = selector;
        _key = key;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return (InetSocketAddress)_channel.socket().getLocalSocketAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return _channel.isOpen();
    }

    @Override
    protected void doShutdownOutput()
    {
    }

    @Override
    public void doClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doClose {}", this);
        try
        {
            _channel.close();
        }
        catch (IOException e)
        {
            LOG.debug("Unable to close channel", e);
        }
        finally
        {
            super.doClose();
        }
    }

    @Override
    public void onClose(Throwable cause)
    {
        try
        {
            super.onClose(cause);
        }
        finally
        {
            if (_selector != null)
                _selector.destroyEndPoint(this, cause);
        }
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (isInputShutdown())
            return -1;

        int pos = BufferUtil.flipToFill(buffer);
        InetSocketAddress peer = (InetSocketAddress)_channel.receive(buffer);
        if (peer == null)
        {
            BufferUtil.flipToFlush(buffer, pos);
            return 0;
        }
        INET_ADDRESS_ARGUMENT.push(peer);

        notIdle();
        BufferUtil.flipToFlush(buffer, pos);
        int filled = buffer.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("filled {} {}", filled, BufferUtil.toDetailString(buffer));
        return filled;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        boolean flushedAll = true;
        long flushed = 0;
        try
        {
            InetSocketAddress peer = INET_ADDRESS_ARGUMENT.pop();
            if (LOG.isDebugEnabled())
                LOG.debug("flushing {} buffer(s) to {}", buffers.length - 1, peer);
            for (ByteBuffer buffer : buffers)
            {
                int sent = _channel.send(buffer, peer);
                if (sent == 0)
                {
                    flushedAll = false;
                    break;
                }
                flushed += sent;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} byte(s), all flushed? {} - {}", flushed, flushedAll, this);
        }
        catch (IOException e)
        {
            throw new EofException(e);
        }

        if (flushed > 0)
            notIdle();

        return flushedAll;
    }

    @Override
    public Object getTransport()
    {
        return _channel;
    }

    @Override
    protected void needsFillInterest()
    {
        changeInterests(SelectionKey.OP_READ);
    }

    @Override
    protected void onIncompleteFlush()
    {
        changeInterests(SelectionKey.OP_WRITE);
    }

    @Override
    public Runnable onSelected()
    {
        // This method runs from the selector thread,
        // possibly concurrently with changeInterests(int).

        int readyOps = _key.readyOps();
        int oldInterestOps;
        int newInterestOps;
        try (AutoLock l = _lock.lock())
        {
            _updatePending = true;
            // Remove the readyOps, that here can only be OP_READ or OP_WRITE (or both).
            oldInterestOps = _desiredInterestOps;
            newInterestOps = oldInterestOps & ~readyOps;
            _desiredInterestOps = newInterestOps;
        }

        boolean fillable = (readyOps & SelectionKey.OP_READ) != 0;
        boolean flushable = (readyOps & SelectionKey.OP_WRITE) != 0;

        if (LOG.isDebugEnabled())
            LOG.debug("onSelected {}->{} r={} w={} for {}", oldInterestOps, newInterestOps, fillable, flushable, this);

        // return task to complete the job
        Runnable task = fillable
            ? (flushable
            ? _runCompleteWriteFillable
            : _runFillable)
            : (flushable
            ? _runCompleteWrite
            : null);

        if (LOG.isDebugEnabled())
            LOG.debug("task {}", task);
        return task;
    }

    private void updateKeyAction(Selector selector)
    {
        updateKey();
    }

    @Override
    public void updateKey()
    {
        // This method runs from the selector thread,
        // possibly concurrently with changeInterests(int).

        try
        {
            int oldInterestOps;
            int newInterestOps;
            try (AutoLock l = _lock.lock())
            {
                _updatePending = false;
                oldInterestOps = _currentInterestOps;
                newInterestOps = _desiredInterestOps;
                if (oldInterestOps != newInterestOps)
                {
                    _currentInterestOps = newInterestOps;
                    _key.interestOps(newInterestOps);
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Key interests updated {} -> {} on {}", oldInterestOps, newInterestOps, this);
        }
        catch (CancelledKeyException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring key update for cancelled key {}", this, x);
            close();
        }
        catch (Throwable x)
        {
            LOG.warn("Ignoring key update for {}", this, x);
            close();
        }
    }

    @Override
    public void replaceKey(SelectionKey newKey)
    {
        _key = newKey;
    }

    private void changeInterests(int operation)
    {
        // This method runs from any thread, possibly
        // concurrently with updateKey() and onSelected().

        int oldInterestOps;
        int newInterestOps;
        boolean pending;
        try (AutoLock l = _lock.lock())
        {
            pending = _updatePending;
            oldInterestOps = _desiredInterestOps;
            newInterestOps = oldInterestOps | operation;
            if (newInterestOps != oldInterestOps)
                _desiredInterestOps = newInterestOps;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("changeInterests p={} {}->{} for {}", pending, oldInterestOps, newInterestOps, this);

        if (!pending && _selector != null)
            _selector.submit(_updateKeyAction);
    }

    @Override
    public String toEndPointString()
    {
        // We do a best effort to print the right toString() and that's it.
        return String.format("%s{io=%d/%d,kio=%d,kro=%d}",
            super.toEndPointString(),
            _currentInterestOps,
            _desiredInterestOps,
            ManagedSelector.safeInterestOps(_key),
            ManagedSelector.safeReadyOps(_key));
    }

    public static final class InetAddressArgument
    {
        private final ThreadLocal<InetSocketAddress> threadLocal = new ThreadLocal<>();

        public void push(InetSocketAddress inetSocketAddress)
        {
            Objects.requireNonNull(inetSocketAddress);
            threadLocal.set(inetSocketAddress);
        }

        public InetSocketAddress pop()
        {
            InetSocketAddress inetSocketAddress = threadLocal.get();
            Objects.requireNonNull(inetSocketAddress);
            threadLocal.remove();
            return inetSocketAddress;
        }
    }
}
