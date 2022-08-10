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

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A convenience base implementation of {@link Connection}.</p>
 * <p>This class uses the capabilities of the {@link EndPoint} API to provide a
 * more traditional style of async reading.  A call to {@link #fillInterested()}
 * will schedule a callback to {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
 * as appropriate.</p>
 */
public abstract class AbstractConnection implements Connection, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractConnection.class);

    private final List<Listener> _listeners = new CopyOnWriteArrayList<>();
    private final long _created = System.currentTimeMillis();
    private final EndPoint _endPoint;
    private final Executor _executor;
    private final Callback _readCallback;
    private int _inputBufferSize = 2048;

    protected AbstractConnection(EndPoint endPoint, Executor executor)
    {
        if (executor == null)
            throw new IllegalArgumentException("Executor must not be null!");
        _endPoint = endPoint;
        _executor = executor;
        _readCallback = new ReadCallback();
    }

    @Deprecated
    @Override
    public InvocationType getInvocationType()
    {
        // TODO consider removing the #fillInterested method from the connection and only use #fillInterestedCallback
        //      so a connection need not be Invocable
        return Invocable.super.getInvocationType();
    }

    @Override
    public void addEventListener(EventListener listener)
    {
        if (listener instanceof Listener)
            _listeners.add((Listener)listener);
    }

    @Override
    public void removeEventListener(EventListener eventListener)
    {
        if (eventListener instanceof Listener listener)
            _listeners.remove(listener);
    }

    public int getInputBufferSize()
    {
        return _inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        _inputBufferSize = inputBufferSize;
    }

    protected Executor getExecutor()
    {
        return _executor;
    }

    protected void failedCallback(final Callback callback, final Throwable x)
    {
        Runnable failCallback = () ->
        {
            try
            {
                callback.failed(x);
            }
            catch (Exception e)
            {
                LOG.warn("Failed callback", x);
            }
        };

        switch (Invocable.getInvocationType(callback))
        {
            case BLOCKING:
                try
                {
                    getExecutor().execute(failCallback);
                }
                catch (RejectedExecutionException e)
                {
                    LOG.debug("Rejected", e);
                    callback.failed(x);
                }
                break;

            case NON_BLOCKING:
                failCallback.run();
                break;

            case EITHER:
                Invocable.invokeNonBlocking(failCallback);
                break;

            default:
                throw new IllegalStateException();
        }
    }

    /**
     * <p>Utility method to be called to register read interest.</p>
     * <p>After a call to this method, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
     * will be called back as appropriate.</p>
     *
     * @see #onFillable()
     */
    public void fillInterested()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fillInterested {}", this);
        getEndPoint().fillInterested(_readCallback);
    }

    public void tryFillInterested(Callback callback)
    {
        getEndPoint().tryFillInterested(callback);
    }

    public boolean isFillInterested()
    {
        return getEndPoint().isFillInterested();
    }

    /**
     * <p>Callback method invoked when the endpoint is ready to be read.</p>
     *
     * @see #fillInterested()
     */
    public abstract void onFillable();

    /**
     * <p>Callback method invoked when the endpoint failed to be ready to be read.</p>
     *
     * @param cause the exception that caused the failure
     */
    protected void onFillInterestedFailed(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillInterestedFailed {}", this, cause);
        if (_endPoint.isOpen())
        {
            boolean close = true;
            if (cause instanceof TimeoutException)
                close = onReadTimeout(cause);
            if (close)
            {
                if (_endPoint.isOutputShutdown())
                    _endPoint.close();
                else
                {
                    _endPoint.shutdownOutput();
                    fillInterested();
                }
            }
        }
    }

    /**
     * <p>Callback method invoked when the endpoint failed to be ready to be read after a timeout</p>
     *
     * @param timeout the cause of the read timeout
     * @return true to signal that the endpoint must be closed, false to keep the endpoint open
     */
    protected boolean onReadTimeout(Throwable timeout)
    {
        return true;
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", this);

        for (Listener listener : _listeners)
        {
            onOpened(listener);
        }
    }

    private void onOpened(Listener listener)
    {
        try
        {
            listener.onOpened(this);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public void onClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            if (cause == null)
                LOG.debug("onClose {}", this);
            else
                LOG.debug("onClose {}", this, cause);
        }
        for (Listener listener : _listeners)
        {
            onClosed(listener);
        }
    }

    private void onClosed(Listener listener)
    {
        try
        {
            listener.onClosed(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.info("Failure while notifying listener {}", listener, x);
            else
                LOG.info("Failure while notifying listener {} {}", listener, x.toString());
        }
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
    public boolean onIdleExpired()
    {
        return true;
    }

    @Override
    public long getMessagesIn()
    {
        return -1;
    }

    @Override
    public long getMessagesOut()
    {
        return -1;
    }

    @Override
    public long getBytesIn()
    {
        return -1;
    }

    @Override
    public long getBytesOut()
    {
        return -1;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return _created;
    }

    @Override
    public final String toString()
    {
        return String.format("%s@%x::%s", getClass().getSimpleName(), hashCode(), getEndPoint());
    }

    public String toConnectionString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    private class ReadCallback implements Callback, Invocable
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), AbstractConnection.this);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return AbstractConnection.this.getInvocationType();
        }
    }
}
