//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.io.Closeable;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An ChannelEndpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements ManagedSelector.SelectableEndPoint
{
    public static final Logger LOG = Log.getLogger(SelectChannelEndPoint.class);

    private final Locker _locker = new Locker();
    private boolean _updatePending;

    /**
     * true if {@link ManagedSelector#destroyEndPoint(EndPoint)} has not been called
     */
    private final AtomicBoolean _open = new AtomicBoolean();
    private final ManagedSelector _selector;
    private final SelectionKey _key;
    /**
     * The current value for {@link SelectionKey#interestOps()}.
     */
    private int _currentInterestOps;
    /**
     * The desired value for {@link SelectionKey#interestOps()}.
     */
    private int _desiredInterestOps;

    private final Runnable _runUpdateKey = new Runnable()
    {
        @Override
        public void run()
        {
            updateKey();
        }

        @Override
        public String toString()
        {
            return SelectChannelEndPoint.this.toString()+":runUpdateKey";
        }
    };

    private abstract class RunnableCloseable implements Runnable, Closeable
    {
        @Override
        public void close()
        {
            try
            {
                SelectChannelEndPoint.this.close();
            }
            catch (Throwable x)
            {
                LOG.warn(x);
            }
        }
    }

    private final Runnable _runFillable = new RunnableCloseable()
    {
        @Override
        public void run()
        {
            getFillInterest().fillable();
        }

        @Override
        public String toString()
        {
            return SelectChannelEndPoint.this.toString()+":runFillable";
        }
    };
    private final Runnable _runCompleteWrite = new RunnableCloseable()
    {
        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
        }

        @Override
        public String toString()
        {
            return SelectChannelEndPoint.this.toString()+":runCompleteWrite";
        }
    };
    private final Runnable _runCompleteWriteFillable = new RunnableCloseable()
    {
        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
            getFillInterest().fillable();
        }

        @Override
        public String toString()
        {
            return SelectChannelEndPoint.this.toString()+":runFillableCompleteWrite";
        }
    };

    public SelectChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler, long idleTimeout)
    {
        super(scheduler, channel);
        _selector = selector;
        _key = key;
        setIdleTimeout(idleTimeout);
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
        /**
         * This method may run concurrently with {@link #changeInterests(int)}.
         */

        int readyOps = _key.readyOps();
        int oldInterestOps;
        int newInterestOps;
        try (Locker.Lock lock = _locker.lock())
        {
            _updatePending = true;
            // Remove the readyOps, that here can only be OP_READ or OP_WRITE (or both).
            oldInterestOps = _desiredInterestOps;
            newInterestOps = oldInterestOps & ~readyOps;
            _desiredInterestOps = newInterestOps;
        }

        boolean readable = (readyOps & SelectionKey.OP_READ) != 0;
        boolean writable = (readyOps & SelectionKey.OP_WRITE) != 0;

        if (LOG.isDebugEnabled())
            LOG.debug("onSelected {}->{} r={} w={} for {}", oldInterestOps, newInterestOps, readable, writable, this);

        // Run non-blocking code immediately.
        // This producer knows that this non-blocking code is special
        // and that it must be run in this thread and not fed to the
        // ExecutionStrategy, which could not have any thread to run these
        // tasks (or it may starve forever just after having run them).
        if (readable && getFillInterest().isCallbackNonBlocking())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Direct readable run {}",this);
            _runFillable.run();
            readable = false;
        }
        if (writable && getWriteFlusher().isCallbackNonBlocking())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Direct writable run {}",this);
            _runCompleteWrite.run();
            writable = false;
        }

        // return task to complete the job
        Runnable task= readable ? (writable ? _runCompleteWriteFillable : _runFillable)
                : (writable ? _runCompleteWrite : null);

        if (LOG.isDebugEnabled())
            LOG.debug("task {}",task);
        return task;
    }

    @Override
    public void updateKey()
    {
        /**
         * This method may run concurrently with {@link #changeInterests(int)}.
         */

        try
        {
            int oldInterestOps;
            int newInterestOps;
            try (Locker.Lock lock = _locker.lock())
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
            LOG.debug("Ignoring key update for concurrently closed channel {}", this);
            close();
        }
        catch (Throwable x)
        {
            LOG.warn("Ignoring key update for " + this, x);
            close();
        }
    }

    private void changeInterests(int operation)
    {
        /**
         * This method may run concurrently with
         * {@link #updateKey()} and {@link #onSelected()}.
         */

        int oldInterestOps;
        int newInterestOps;
        boolean pending;
        try (Locker.Lock lock = _locker.lock())
        {
            pending = _updatePending;
            oldInterestOps = _desiredInterestOps;
            newInterestOps = oldInterestOps | operation;
            if (newInterestOps != oldInterestOps)
                _desiredInterestOps = newInterestOps;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("changeInterests p={} {}->{} for {}", pending, oldInterestOps, newInterestOps, this);

        if (!pending)
            _selector.submit(_runUpdateKey);
    }


    @Override
    public void close()
    {
        if (_open.compareAndSet(true, false))
        {
            super.close();
            _selector.destroyEndPoint(this);
        }
    }

    @Override
    public boolean isOpen()
    {
        // We cannot rely on super.isOpen(), because there is a race between calls to close() and isOpen():
        // a thread may call close(), which flips the boolean but has not yet called super.close(), and
        // another thread calls isOpen() which would return true - wrong - if based on super.isOpen().
        return _open.get();
    }

    @Override
    public void onOpen()
    {
        if (_open.compareAndSet(false, true))
            super.onOpen();
    }

    @Override
    public String toString()
    {
        // We do a best effort to print the right toString() and that's it.
        try
        {
            boolean valid = _key != null && _key.isValid();
            int keyInterests = valid ? _key.interestOps() : -1;
            int keyReadiness = valid ? _key.readyOps() : -1;
            return String.format("%s{io=%d/%d,kio=%d,kro=%d}",
                    super.toString(),
                    _currentInterestOps,
                    _desiredInterestOps,
                    keyInterests,
                    keyReadiness);
        }
        catch (Throwable x)
        {
            return String.format("%s{io=%s,kio=-2,kro=-2}", super.toString(), _desiredInterestOps);
        }
    }
}
