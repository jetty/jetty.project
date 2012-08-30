//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExecutorCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A convenience base implementation of {@link Connection}.</p>
 * <p>This class uses the capabilities of the {@link EndPoint} API to provide a
 * more traditional style of async reading.  A call to {@link #fillInterested()}
 * will schedule a callback to {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
 * as appropriate.</p>
 */
public abstract class AbstractConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(AbstractConnection.class);

    private final AtomicBoolean _readInterested = new AtomicBoolean();
    private final EndPoint _endPoint;
    private final Executor _executor;
    private final Callback<Void> _readCallback;

    public AbstractConnection(EndPoint endp, Executor executor)
    {
        this(endp, executor, true);
    }

    public AbstractConnection(EndPoint endp, Executor executor, final boolean dispatchCompletion)
    {
        if (executor == null)
            throw new IllegalArgumentException("Executor must not be null!");
        _endPoint = endp;
        _executor = executor;
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
            protected boolean shouldDispatchCompletion()
            {
                return dispatchCompletion;
            }

            @Override
            public String toString()
            {
                return String.format("%s@%x", getClass().getSimpleName(), AbstractConnection.this.hashCode());
            }
        };
    }

    public Executor getExecutor()
    {
        return _executor;
    }

    /**
     * <p>Utility method to be called to register read interest.</p>
     * <p>After a call to this method, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
     * will be called back as appropriate.</p>
     * @see #onFillable()
     */
    public void fillInterested()
    {
        LOG.debug("fillInterested {}",this);
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
        if (_endPoint.isOpen())
        {
            boolean close = true;
            if (cause instanceof TimeoutException)
                close = onReadTimeout();
            if (close)
            {
                if (_endPoint.isOutputShutdown())
                    _endPoint.close();
                else
                    _endPoint.shutdownOutput();
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
    }

    @Override
    public void onClose()
    {
        LOG.debug("{} closed",this);
    }

    @Override
    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    @Override
    public void close()
    {
        getEndPoint().close();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _readInterested.get() ? "R" : "");
    }
}
