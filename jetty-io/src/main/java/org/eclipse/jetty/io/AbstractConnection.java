//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.NonBlockingThread;

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
    private final AtomicReference<State> _state = new AtomicReference<>(IDLE);
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
        _state.set(IDLE);
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
    
    protected void failedCallback(final Callback callback, final Throwable x)
    {
        if (NonBlockingThread.isNonBlockingThread())
        {
            try
            {
                getExecutor().execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        callback.failed(x);
                    }
                });
            }
            catch(RejectedExecutionException e)
            {
                LOG.debug(e);
                callback.failed(x);
            }
        }
        else
        {
            callback.failed(x);
        }
    }
    
    /**
     * <p>Utility method to be called to register read interest.</p>
     * <p>After a call to this method, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
     * will be called back as appropriate.</p>
     * @see #onFillable()
     */
    public void fillInterested()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fillInterested {}",this);
        
        while(true)
        {
            State state=_state.get();
            if (next(state,state.fillInterested()))
                break;
        }
    }
    
    public void fillInterested(Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fillInterested {}",this);

        while(true)
        {
            State state=_state.get();
            // TODO yuck
            if (state instanceof FillingInterestedCallback && ((FillingInterestedCallback)state)._callback==callback)
                break;
            State next=new FillingInterestedCallback(callback,state);
            if (next(state,next))
                break;
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
        if (LOG.isDebugEnabled())
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

        if (_endPoint.isOpen())
            fillInterested();        
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
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", this);

        for (Listener listener : listeners)
            listener.onOpened(this);
    }

    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
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
    
    public boolean next(State state, State next)
    {
        if (next==null)
            return true;
        if(_state.compareAndSet(state,next))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{}-->{} {}",state,next,this);
            if (next!=state)
                next.onEnter(AbstractConnection.this);
            return true;
        }
        return false;
    }
    
    private static final class IdleState extends State
    {
        private IdleState()
        {
            super("IDLE");
        }

        @Override
        State fillInterested()
        {
            return FILL_INTERESTED;
        }
    }


    private static final class FillInterestedState extends State
    {
        private FillInterestedState()
        {
            super("FILL_INTERESTED");
        }

        @Override
        public void onEnter(AbstractConnection connection)
        {
            connection.getEndPoint().fillInterested(connection._readCallback);
        }

        @Override
        State fillInterested()
        {
            return this;
        }

        @Override
        public State onFillable()
        {
            return FILLING;
        }

        @Override
        State onFailed()
        {
            return IDLE;
        }
    }


    private static final class RefillingState extends State
    {
        private RefillingState()
        {
            super("REFILLING");
        }

        @Override
        State fillInterested()
        {
            return FILLING_FILL_INTERESTED;
        }

        @Override
        public State onFilled()
        {
            return IDLE;
        }
    }


    private static final class FillingFillInterestedState extends State
    {
        private FillingFillInterestedState(String name)
        {
            super(name);
        }

        @Override
        State fillInterested()
        {
            return this;
        }

        State onFilled()
        {
            return FILL_INTERESTED;
        }
    }


    private static final class FillingState extends State
    {
        private FillingState()
        {
            super("FILLING");
        }

        @Override
        public void onEnter(AbstractConnection connection)
        {
            if (connection._executeOnfillable)
                connection.getExecutor().execute(connection._runOnFillable);
            else
                connection._runOnFillable.run();
        }

        @Override
        State fillInterested()
        {
            return FILLING_FILL_INTERESTED;
        }

        @Override
        public State onFilled()
        {
            return IDLE;
        }
    }


    public static class State
    {
        private final String _name;
        State(String name)
        {
            _name=name;
        }

        @Override
        public String toString()
        {
            return _name;
        }
        
        void onEnter(AbstractConnection connection)
        {
        }
        
        State fillInterested()
        {
            throw new IllegalStateException(this.toString());
        }

        State onFillable()
        {
            throw new IllegalStateException(this.toString());
        }

        State onFilled()
        {
            throw new IllegalStateException(this.toString());
        }
        
        State onFailed()
        {
            throw new IllegalStateException(this.toString());
        }
    }
    

    public static final State IDLE=new IdleState();
    
    public static final State FILL_INTERESTED=new FillInterestedState();
    
    public static final State FILLING=new FillingState();
    
    public static final State REFILLING=new RefillingState();

    public static final State FILLING_FILL_INTERESTED=new FillingFillInterestedState("FILLING_FILL_INTERESTED");
    
    public class NestedState extends State
    {
        private final State _nested;
        
        NestedState(State nested)
        {
            super("NESTED("+nested+")");
            _nested=nested;
        }
        NestedState(String name,State nested)
        {
            super(name+"("+nested+")");
            _nested=nested;
        }

        @Override
        State fillInterested()
        {
            return new NestedState(_nested.fillInterested());
        }

        @Override
        State onFillable()
        {
            return new NestedState(_nested.onFillable());
        }
        
        @Override
        State onFilled()
        {
            return new NestedState(_nested.onFilled());
        }
    }
    
    
    public class FillingInterestedCallback extends NestedState
    {
        private final Callback _callback;
        
        FillingInterestedCallback(Callback callback,State nested)
        {
            super("FILLING_INTERESTED_CALLBACK",nested==FILLING?REFILLING:nested);
            _callback=callback;
        }

        @Override
        void onEnter(final AbstractConnection connection)
        {
            Callback callback=new Callback()
            {
                @Override
                public void succeeded()
                {
                    while(true)
                    {
                        State state = connection._state.get();
                        if (!(state instanceof NestedState))
                            break;
                        State nested=((NestedState)state)._nested;
                        if (connection.next(state,nested))
                            break;
                    }
                    _callback.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    while(true)
                    {
                        State state = connection._state.get();
                        if (!(state instanceof NestedState))
                            break;
                        State nested=((NestedState)state)._nested;
                        if (connection.next(state,nested))
                            break;
                    }
                    _callback.failed(x);
                }  
            };
            
            connection.getEndPoint().fillInterested(callback);
        }
    }
    
    private final Runnable _runOnFillable = new Runnable()
    {
        @Override
        public void run()
        {
            try
            {
                onFillable();
            }
            finally
            {
                while(true)
                {
                    State state=_state.get();
                    if (next(state,state.onFilled()))
                        break;
                }
            }
        }
    };
    
    
    private class ReadCallback implements Callback
    {   
        @Override
        public void succeeded()
        {
            while(true)
            {
                State state=_state.get();
                if (next(state,state.onFillable()))
                    break;
            }
        }

        @Override
        public void failed(final Throwable x)
        {
            _executor.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    while(true)
                    {
                        State state=_state.get();
                        if (next(state,state.onFailed()))
                            break;
                    }
                    onFillInterestedFailed(x);
                }
            });
        }
        
        @Override
        public String toString()
        {
            return String.format("AC.ReadCB@%x{%s}", AbstractConnection.this.hashCode(),AbstractConnection.this);
        }
    };
}
