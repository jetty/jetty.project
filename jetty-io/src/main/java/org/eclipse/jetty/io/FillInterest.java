//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.atomic.AtomicReference;

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
        if (callback == null)
            throw new IllegalArgumentException();

        if (!_interested.compareAndSet(null, callback))
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("interested {}", this);

        try
        {
            needsFillInterest();
        }
        catch (Throwable e)
        {
            onFail(e);
        }

        return true;
    }

    /**
     * Call to signal that a read is now possible.
     *
     * @return whether the callback was notified that a read is now possible
     */
    public boolean fillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fillable {}", this);
        Callback callback = _interested.get();
        if (callback != null && _interested.compareAndSet(callback, null))
        {
            callback.succeeded();
            return true;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} lost race {}", this, callback);
        return false;
    }

    /**
     * @return True if a read callback has been registered
     */
    public boolean isInterested()
    {
        return _interested.get() != null;
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
        if (LOG.isDebugEnabled())
            LOG.debug("onFail " + this, cause);
        Callback callback = _interested.get();
        if (callback != null && _interested.compareAndSet(callback, null))
        {
            callback.failed(cause);
            return true;
        }
        return false;
    }

    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose {}", this);
        Callback callback = _interested.get();
        if (callback != null && _interested.compareAndSet(callback, null))
            callback.failed(new ClosedChannelException());
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
}
