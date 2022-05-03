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

package org.eclipse.jetty.io;

import java.io.Closeable;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A partial {@link EndPoint} implementation based on {@link SelectableChannel}.</p>
 */
public abstract class SelectableChannelEndPoint extends AbstractEndPoint implements ManagedSelector.Selectable
{
    private static final Logger LOG = LoggerFactory.getLogger(SelectableChannelEndPoint.class);

    private final AutoLock _lock = new AutoLock();
    private final SelectableChannel _channel;
    private final ManagedSelector _selector;
    private SelectionKey _key;
    private boolean _updatePending;
    // The current value for interestOps.
    private int _currentInterestOps;
    // The desired value for interestOps.
    private int _desiredInterestOps;
    private final ManagedSelector.SelectorUpdate _updateKeyAction = this::updateKeyAction;
    private final Runnable _runFillable = new RunnableCloseable("runFillable")
    {
        @Override
        public void run()
        {
            getFillInterest().fillable();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getFillInterest().getCallbackInvocationType();
        }
    };
    private final Runnable _runCompleteWrite = new RunnableCloseable("runCompleteWrite")
    {
        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getWriteFlusher().getCallbackInvocationType();
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s:%s->%s", SelectableChannelEndPoint.this, _operation, getInvocationType(), getWriteFlusher());
        }
    };
    private final Runnable _runCompleteWriteFillable = new RunnableCloseable("runCompleteWriteFillable")
    {
        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
            getFillInterest().fillable();
        }

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
    };

    public SelectableChannelEndPoint(Scheduler scheduler, SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        super(scheduler);
        _channel = channel;
        _selector = selector;
        _key = selectionKey;
    }

    public SelectableChannel getChannel()
    {
        return _channel;
    }

    @Override
    public Object getTransport()
    {
        return getChannel();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        try
        {
            SelectableChannel channel = getChannel();
            if (channel instanceof NetworkChannel)
                return ((NetworkChannel)channel).getLocalAddress();
            return super.getLocalSocketAddress();
        }
        catch (Throwable x)
        {
            LOG.trace("Could not retrieve local socket address", x);
            return null;
        }
    }

    @Override
    public boolean isOpen()
    {
        return _channel.isOpen();
    }

    @Override
    public void doClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doClose {}", this);
        IO.close(_channel);
        super.doClose();
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
    protected void needsFillInterest()
    {
        changeInterests(SelectionKey.OP_READ);
    }

    @Override
    protected void onIncompleteFlush()
    {
        changeInterests(SelectionKey.OP_WRITE);
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

    private abstract class RunnableCloseable implements Invocable.Task, Closeable
    {
        final String _operation;

        private RunnableCloseable(String operation)
        {
            _operation = operation;
        }

        @Override
        public void close()
        {
            try
            {
                SelectableChannelEndPoint.this.close();
            }
            catch (Throwable x)
            {
                LOG.warn("Unable to close {}", SelectableChannelEndPoint.this, x);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s:%s", SelectableChannelEndPoint.this, _operation, getInvocationType());
        }
    }
}
