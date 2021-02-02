//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadPendingException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;

/**
 * A Utility class to help implement {@link EndPoint#fillInterested(Callback)}
 * by keeping state and calling the context and callback objects.
 */
public abstract class FillInterest
{
    private static final Logger LOG = Log.getLogger(FillInterest.class);
    private final AtomicReference<Callback> _interested = new AtomicReference<>(null);

    protected FillInterest()
    {
    }

    /**
     * Cancel a fill interest registration.
     *
     * If there was a registration, then any {@link #fillable()}, {@link #onClose()} or {@link #onFail(Throwable)}
     * calls are remembered and passed to the next registration.
     * Since any actions resulting from a call to {@link #needsFillInterest()} cannot be unwound, a subsequent call to
     * register will not call {@link #needsFillInterest()} again if it has already been called an no callback received.
     * @param cancellation A supplier of the cancellation Throwable to use if there is an existing registration. If the
     * suppler or the supplied Throwable is null, then a new {@link CancellationException} is used.
     * @return The Throwable used to cancel an existing registration or null if there was no registration to cancel.
     */
    public Throwable cancel(Supplier<Throwable> cancellation)
    {
        Cancelled cancelled = new Cancelled();
        while (true)
        {
            Callback callback = _interested.get();
            if (callback == null || callback instanceof Cancelled)
                return null;
            if (_interested.compareAndSet(callback, cancelled))
            {
                Throwable cause = cancellation == null ? null : cancellation.get();
                if (cause == null)
                    cause = new CancellationException();
                if (LOG.isDebugEnabled())
                    LOG.debug("cancelled {} {}",this, callback, cause);
                callback.failed(cause);
                return cause;
            }
        }
    }

    /**
     * Call to register interest in a callback when a read is possible.
     * The callback will be called either immediately if {@link #needsFillInterest()}
     * returns true or eventually once {@link #fillable()} is called.
     *
     * @param callback the callback to register
     * @throws ReadPendingException if unable to read due to pending read op
     */
    public void register(Callback callback) throws ReadPendingException
    {
        if (!tryRegister(callback))
        {
            LOG.warn("Read pending for {} prevented {}", _interested, callback);
            throw new ReadPendingException();
        }
    }

    /**
     * Call to register interest in a callback when a read is possible.
     * The callback will be called either immediately if {@link #needsFillInterest()}
     * returns true or eventually once {@link #fillable()} is called.
     *
     * @param callback the callback to register
     * @return true if the register succeeded
     */
    public boolean tryRegister(Callback callback)
    {
        return register(callback, null);
    }

    /**
     * Call to register interest in a callback when a read is possible.
     * The callback will be called either immediately if {@link #needsFillInterest()}
     * returns true or eventually once {@link #fillable()} is called.
     *
     * @param callback the callback to register
     * @param cancellation A supplier of a {@link Throwable}, which if not null will be used to fail any existing registration
     * @return true if the register succeeded
     */
    public boolean register(Callback callback, Supplier<Throwable> cancellation)
    {
        if (callback == null)
            throw new IllegalArgumentException();

        while (true)
        {
            Callback existing = _interested.get();

            if (existing != null && !(existing instanceof Cancelled) && cancellation == null)
                return false;

            if (existing == callback)
                return true;

            if (_interested.compareAndSet(existing, callback))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interested {}->{}", existing, this);
                if (existing == null)
                {
                    try
                    {
                        needsFillInterest();
                    }
                    catch (Throwable e)
                    {
                        onFail(e);
                    }
                }
                else if (existing instanceof Cancelled)
                {
                    ((Cancelled)existing).apply(callback);
                }
                else
                {
                    Throwable cause = cancellation.get();
                    if (cause == null)
                        cause = new CancellationException();
                    existing.failed(cause);
                }
                return true;
            }
        }
    }

    /**
     * Call to signal that a read is now possible.
     *
     * @return whether the callback was notified that a read is now possible
     */
    public boolean fillable()
    {
        while (true)
        {
            Callback callback = _interested.get();
            if (callback == null)
                return false;
            if (_interested.compareAndSet(callback, null))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("fillable {} {}",this, callback);
                callback.succeeded();
                return true;
            }
        }
    }

    /**
     * @return True if a read callback has been registered
     */
    public boolean isInterested()
    {
        Callback callback = _interested.get();
        return callback != null && !(callback instanceof Cancelled);
    }

    public InvocationType getCallbackInvocationType()
    {
        Callback callback = _interested.get();
        return Invocable.getInvocationType(callback);
    }

    /**
     * Call to signal a failure to a registered interest
     *
     * @param cause the cause of the failure
     * @return true if the cause was passed to a {@link Callback} instance
     */
    public boolean onFail(Throwable cause)
    {
        while (true)
        {
            Callback callback = _interested.get();
            if (callback == null)
                return false;
            if (_interested.compareAndSet(callback, null))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onFail {} {}",this, callback, cause);
                callback.failed(cause);
                return true;
            }
        }
    }

    public void onClose()
    {
        while (true)
        {
            Callback callback = _interested.get();
            if (callback == null)
                return;
            if (_interested.compareAndSet(callback, null))
            {
                ClosedChannelException cause = new ClosedChannelException();
                if (LOG.isDebugEnabled())
                    LOG.debug("onFail {} {}",this, callback, cause);
                callback.failed(cause);
                return;
            }
        }
    }

    @Override
    public String toString()
    {
        return String.format("FillInterest@%x{%s}", hashCode(), _interested.get());
    }

    public String toStateString()
    {
        return _interested.get() == null ? "-" : "FI";
    }

    /**
     * Register the read interest
     * Abstract method to be implemented by the Specific ReadInterest to
     * schedule a future call to {@link #fillable()} or {@link #onFail(Throwable)}
     *
     * @throws IOException if unable to fulfill interest in fill
     */
    protected abstract void needsFillInterest() throws IOException;

    private static class Cancelled implements Callback
    {
        private final AtomicReference<Object> _result = new AtomicReference<>();

        @Override
        public void succeeded()
        {
            _result.compareAndSet(null, Boolean.TRUE);
        }

        @Override
        public void failed(Throwable x)
        {
            _result.compareAndSet(null, x == null ? new Exception() : x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        void apply(Callback callback)
        {
            Object result = _result.get();
            if (result == Boolean.TRUE)
                callback.succeeded();
            else if (result instanceof Throwable)
                callback.failed((Throwable)result);
        }
    }
}
