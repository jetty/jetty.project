//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.util.Callback;
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
    
    public static final boolean EXECUTE_ONFILLABLE=true;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<State> _state = new AtomicReference<>(State.IDLE);
    private final long _created=System.currentTimeMillis();
    private final EndPoint _endPoint;
    private final Executor _executor;
    private final Callback _readCallback;
    private final boolean _executeOnfillable;
    private int _inputBufferSize=2048;

    protected AbstractConnection(EndPoint endp, Executor executor)
    {
        this(endp,executor,EXECUTE_ONFILLABLE);
    }
    
    protected AbstractConnection(EndPoint endp, Executor executor, final boolean executeOnfillable)
    {
        if (executor == null)
            throw new IllegalArgumentException("Executor must not be null!");
        _endPoint = endp;
        _executor = executor;
        _readCallback = new ReadCallback();
        _executeOnfillable=executeOnfillable;
    }

    @Override
    public void addListener(Listener listener)
    {
        listeners.add(listener);
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
    
    /**
     * <p>Utility method to be called to register read interest.</p>
     * <p>After a call to this method, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
     * will be called back as appropriate.</p>
     * @see #onFillable()
     */
    public void fillInterested()
    {
        LOG.debug("fillInterested {}",this);

        loop:while(true)
        {
            switch(_state.get())
            {
                case IDLE:
                    if (_state.compareAndSet(State.IDLE,State.INTERESTED))
                    {
                        getEndPoint().fillInterested(_readCallback);
                        break loop;
                    }
                    break;

                case FILLING:
                    if (_state.compareAndSet(State.FILLING,State.FILLING_INTERESTED))
                        break loop;
                    break;
                    
                case FILLING_BLOCKED:
                    if (_state.compareAndSet(State.FILLING_BLOCKED,State.FILLING_BLOCKED_INTERESTED))
                        break loop;
                    break;
                    
                case BLOCKED:
                    if (_state.compareAndSet(State.BLOCKED,State.BLOCKED_INTERESTED))
                        break loop;
                    break;

                case FILLING_BLOCKED_INTERESTED:
                case FILLING_INTERESTED:
                case BLOCKED_INTERESTED:
                case INTERESTED:
                    break loop;
            }
        }
    }
    

    private void unblock()
    {
        LOG.debug("unblock {}",this);

        loop:while(true)
        {
            switch(_state.get())
            {
                case FILLING_BLOCKED:
                    if (_state.compareAndSet(State.FILLING_BLOCKED,State.FILLING))
                        break loop;
                    break;
                    
                case FILLING_BLOCKED_INTERESTED:
                    if (_state.compareAndSet(State.FILLING_BLOCKED_INTERESTED,State.FILLING_INTERESTED))
                        break loop;
                    break;
                    
                case BLOCKED_INTERESTED:
                    if (_state.compareAndSet(State.BLOCKED_INTERESTED,State.INTERESTED))
                    {
                        getEndPoint().fillInterested(_readCallback);
                        break loop;
                    }
                    break;
                    
                case BLOCKED:
                    if (_state.compareAndSet(State.BLOCKED,State.IDLE))
                        break loop;
                    break;

                case FILLING:
                case IDLE:
                case FILLING_INTERESTED:
                case INTERESTED:
                    break loop;
            }
        }
    }
    
    
    /**
     */
    protected void block(final BlockingCallback callback)
    {
        LOG.debug("block {}",this);
        
        final Callback blocked=new Callback()
        {
            @Override
            public void succeeded()
            {
                unblock();
                callback.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                unblock();
                callback.failed(x);                
            }
        };

        loop:while(true)
        {
            switch(_state.get())
            {
                case IDLE:
                    if (_state.compareAndSet(State.IDLE,State.BLOCKED))
                    {
                        getEndPoint().fillInterested(blocked);
                        break loop;
                    }
                    break;

                case FILLING:
                    if (_state.compareAndSet(State.FILLING,State.FILLING_BLOCKED))
                    {
                        getEndPoint().fillInterested(blocked);
                        break loop;
                    }
                    break;
                    
                case FILLING_INTERESTED:
                    if (_state.compareAndSet(State.FILLING_INTERESTED,State.FILLING_BLOCKED_INTERESTED))
                    {
                        getEndPoint().fillInterested(blocked);
                        break loop;
                    }
                    break;

                case BLOCKED:
                case BLOCKED_INTERESTED:
                case FILLING_BLOCKED:
                case FILLING_BLOCKED_INTERESTED:
                    throw new IllegalStateException("Already Blocked");
                    
                case INTERESTED:
                    throw new IllegalStateException();
            }
        }
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
    protected void onFillInterestedFailed(Throwable cause)
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
        LOG.debug("onOpen {}", this);

        for (Listener listener : listeners)
            listener.onOpened(this);
    }

    @Override
    public void onClose()
    {
        LOG.debug("onClose {}",this);

        for (Listener listener : listeners)
            listener.onClosed(this);
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
    public int getMessagesIn()
    {
        return -1;
    }

    @Override
    public int getMessagesOut()
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
    public String toString()
    {
        return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), _state.get());
    }

    private enum State
    {
        IDLE, INTERESTED, FILLING, FILLING_INTERESTED, FILLING_BLOCKED, BLOCKED, FILLING_BLOCKED_INTERESTED, BLOCKED_INTERESTED
    }
    
    private class ReadCallback implements Callback, Runnable
    {
        @Override
        public void run()
        {
            if (_state.compareAndSet(State.INTERESTED,State.FILLING))
            {
                try
                {
                    onFillable();
                }
                finally
                {
                    loop:while(true)
                    {
                        switch(_state.get())
                        {
                            case IDLE:
                            case INTERESTED:
                            case BLOCKED:
                            case BLOCKED_INTERESTED:
                                LOG.warn(new IllegalStateException());
                                return;

                            case FILLING:
                                if (_state.compareAndSet(State.FILLING,State.IDLE))
                                    break loop;
                                break;
                                
                            case FILLING_BLOCKED:
                                if (_state.compareAndSet(State.FILLING_BLOCKED,State.BLOCKED))
                                    break loop;
                                break;
                                
                            case FILLING_BLOCKED_INTERESTED:
                                if (_state.compareAndSet(State.FILLING_BLOCKED_INTERESTED,State.BLOCKED_INTERESTED))
                                    break loop;
                                break;

                            case FILLING_INTERESTED:
                                if (_state.compareAndSet(State.FILLING_INTERESTED,State.INTERESTED))
                                {
                                    getEndPoint().fillInterested(_readCallback);
                                    break loop;
                                }
                                break;
                        }
                    }
                }
            }
            else
                LOG.warn(new IllegalStateException());
        }
        
        @Override
        public void succeeded()
        {
            if (_executeOnfillable)
                _executor.execute(this);
            else
                run();
        }

        @Override
        public void failed(final Throwable x)
        {
            _executor.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    onFillInterestedFailed(x);
                }
            });
        }
        
        @Override
        public String toString()
        {
            return String.format("AC.ExReadCB@%x", AbstractConnection.this.hashCode());
        }
    };
}
