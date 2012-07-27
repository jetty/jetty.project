// ========================================================================
// Copyright (c) 2004-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExecutorCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A convenience base implementation of {@link AsyncConnection}.</p>
 * <p>This class uses the capabilities of the {@link AsyncEndPoint} API to provide a
 * more traditional style of async reading.  A call to {@link #fillInterested()}
 * will schedule a callback to {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
 * as appropriate.</p>
 */
public abstract class AbstractAsyncConnection implements AsyncConnection
{
    private static final Logger LOG = Log.getLogger(AbstractAsyncConnection.class);

    private final AtomicBoolean _readInterested = new AtomicBoolean();
    private final AsyncEndPoint _endp;
    private final Callback<Void> _readCallback;

    public AbstractAsyncConnection(AsyncEndPoint endp, Executor executor)
    {
        this(endp, executor, false);
    }

    public AbstractAsyncConnection(AsyncEndPoint endp, Executor executor, final boolean executeOnlyFailure)
    {
        if (executor == null)
            throw new IllegalArgumentException("Executor must not be null!");

        _endp = endp;
        _readCallback = new ExecutorCallback<Void>(executor)
        {
            @Override
            protected void onCompleted(Void context)
            {
                if (_readInterested.compareAndSet(true, false))
                    onFillable();
            }

            @Override
            protected void onFailed(Void context, Throwable x)
            {
                onFillInterestedFailed(x);
            }

            @Override
            protected boolean execute()
            {
                return !executeOnlyFailure;
            }

            @Override
            public String toString()
            {
                return String.format("%s@%x", getClass().getSimpleName(), AbstractAsyncConnection.this.hashCode());
            }
        };
    }

    /**
     * <p>Utility method to be called to register read interest.</p>
     * <p>After a call to this method, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
     * will be called back as appropriate.</p>
     * @see #onFillable()
     */
    public void fillInterested()
    {
        if (_readInterested.compareAndSet(false, true))
            getEndPoint().fillInterested(null, _readCallback);
    }

    /**
     * <p>Callback method invoked when the endpoint is ready to be read.</p>
     * @see #fillInterested()
     */
    public abstract void onFillable();

    /**
     * <p>Callback method invoked when the endpoint failed to be ready to be read.</p>
     * @param cause the exception that caused the failure
     */
    public void onFillInterestedFailed(Throwable cause)
    {
        LOG.debug("{} onFillInterestedFailed {}", this, cause);
        if (_endp.isOpen())
        {
            boolean close = true;
            if (cause instanceof TimeoutException)
                close = onReadTimeout();
            if (close)
            {
                if (_endp.isOutputShutdown())
                    _endp.close();
                else
                    _endp.shutdownOutput();
            }
        }
    }

    /**
     * <p>Callback method invoked when the endpoint failed to be ready to be read after a timeout</p>
     * @return true to signal that the endpoint must be closed, false to keep the endpoint open
     */
    protected boolean onReadTimeout()
    {
        return true;
    }

    @Override
    public void onOpen()
    {
        LOG.debug("{} opened",this);
        fillInterested();
    }

    @Override
    public void onClose()
    {
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return _endp;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _readInterested.get() ? "R" : "");
    }
}
