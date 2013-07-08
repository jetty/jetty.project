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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;

import javax.servlet.ServletInputStream;
import javax.servlet.ReadListener;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link HttpInput} provides an implementation of {@link ServletInputStream} for {@link HttpChannel}.</p>
 * <p>{@link HttpInput} holds a queue of items passed to it by calls to {@link #content(T)}.</p>
 * <p>{@link HttpInput} stores the items directly; if the items contain byte buffers, it does not copy them
 * but simply holds references to the item, thus the caller must organize for those buffers to valid while
 * held by this class.</p>
 * <p>To assist the caller, subclasses may override methods {@link #onContentQueued(T)},
 * {@link #onContentConsumed(T)} and {@link #onAllContentConsumed()} that can be implemented so that the
 * caller will know when buffers are queued and consumed.</p>
 */
public abstract class HttpInput<T> extends ServletInputStream implements Runnable
{
    private final static Logger LOG = Log.getLogger(HttpInput.class);
    
    private HttpChannelState _channelState;
    private Throwable _onError;
    private ReadListener _listener;
    private boolean _notReady;
    
    protected State _state = BLOCKING;
    private State _eof=null;
    
    
    protected HttpInput()
    {
    }
    
    public abstract Object lock();

    public void recycle()
    {
        synchronized (lock())
        {
            _state = BLOCKING;
            _eof=null;
            _onError=null;
        }
    }

    protected abstract T nextContent() throws IOException;
    
    protected void checkEOF()
    {
        if (_eof!=null)   
        {
            LOG.debug("{} eof {}",this,_eof);
            _state=_eof;
            _eof=null;
        }
    }
    
    @Override
    public int read() throws IOException
    {
        byte[] bytes = new byte[1];
        int read = read(bytes, 0, 1);
        return read < 0 ? -1 : 0xff & bytes[0];
    }

    @Override
    public int available()
    {
        try
        {
            synchronized (lock())
            {
                T item = nextContent();
                if (item==null)
                {
                    checkEOF();
                    return 0;
                }
                return remaining(item);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        T item = null;
        synchronized (lock())
        {
            // System.err.printf("read s=%s q=%d e=%s%n",_state,_inputQ.size(),_eof);

            // Get the current head of the input Q
            item = nextContent();
            
            // If we have no item
            if (item == null)
            {
                checkEOF();
                _state.waitForContent(this);
                item=nextContent();
                if (item==null)
                {
                    checkEOF();
                    return _state.noContent();
                }
            }
        }

        return get(item, b, off, len);
    }
    protected abstract int remaining(T item);

    protected abstract int get(T item, byte[] buffer, int offset, int length);

    protected abstract void blockForContent() throws IOException;
    
    protected boolean onAsyncRead()
    {
        if (_listener==null)
            return false;
        _channelState.onReadPossible();
        return true;
    }

    /* ------------------------------------------------------------ */
    /** Add some content to the input stream
     * @param item
     */
    public abstract void content(T item);
    

    /* ------------------------------------------------------------ */
    /** This method should be called to signal to the HttpInput
     * that an EOF has arrived before all the expected content.
     * Typically this will result in an EOFException being thrown
     * from a subsequent read rather than a -1 return.
     */
    public void earlyEOF()
    {
        synchronized (lock())
        {
            if (_eof==null || !_eof.isEOF())
            {
                LOG.debug("{} early EOF", this);
                _eof=EARLY_EOF;
                if (_listener!=null)
                    _channelState.onReadPossible();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void messageComplete()
    {
        synchronized (lock())
        {
            if (_eof==null || !_eof.isEOF())
            {
                LOG.debug("{} EOF", this);
                _eof=EOF;
                if (_listener!=null)
                    _channelState.onReadPossible();
            }
        }
    }

    public abstract void consumeAll();

    @Override
    public boolean isFinished()
    {
        synchronized (lock())
        {
            return _state.isEOF();
        }
    }

    @Override
    public boolean isReady()
    {
        synchronized (lock())
        {
            if (_listener==null)
                return true;
            int available = available();
            if (available>0)
                return true;
            if (!_notReady)
            {
                _notReady=true;
                if (_state.isEOF())
                    _channelState.onReadPossible();
                else
                    unready();
            }
            return false;
        }
    }

    protected void unready()
    {
    }
    
    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (readListener==null)
            throw new NullPointerException("readListener==null");
        synchronized (lock())
        {
            if (_state!=BLOCKING)
                throw new IllegalStateException("state="+_state);
            _state=ASYNC;
            _listener=readListener;
            _notReady=true;

            _channelState.onReadPossible();
        }
    }

    public void failed(Throwable x)
    {
        synchronized (lock())
        {
            if (_onError==null)
                LOG.warn(x);
            else
                _onError=x; 
        }
    }
    
    @Override
    public void run()
    {
        final boolean available;
        final boolean eof;
        final Throwable x;
        
        synchronized (lock())
        {
            if (!_notReady || _listener==null)
                return;

            x=_onError;
            T item;
            try
            {
                item = nextContent();
            }
            catch(Exception e)
            {
                item=null;
                failed(e);
            }
            available= item!=null && remaining(item)>0;

            eof = !available && _state.isEOF();
            _notReady=!available&&!eof;
        }

        try
        {
            if (x!=null)
                _listener.onError(_onError);
            else if (available)
                _listener.onDataAvailable();
            else if (eof)
                _listener.onAllDataRead();
            else
                unready();
        }
        catch(Exception e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
            _listener.onError(e);
        }
    }  
    

    protected static class State
    {
        public void waitForContent(HttpInput<?> in) throws IOException
        {
        }
        
        public int noContent() throws IOException
        {
            return -1;
        }
        
        public boolean isEOF()
        {
            return false;
        }
    }

    protected static final State BLOCKING= new State()
    {
        @Override
        public void waitForContent(HttpInput<?> in) throws IOException
        {
            in.blockForContent();
        }
        public String toString()
        {
            return "OPEN";
        }
    };
    
    protected static final State ASYNC= new State()
    {
        @Override
        public int noContent() throws IOException
        {
            return 0;
        }
        @Override 
        public String toString()
        {
            return "ASYNC";
        }
    };
    
    protected static final State EARLY_EOF= new State()
    {
        @Override        
        public int noContent() throws IOException
        {
            throw new EofException();
        }
        @Override
        public boolean isEOF()
        {
            return true;
        }
        public String toString()
        {
            return "EARLY_EOF";
        }
    };

    protected static final State EOF= new State()
    {
        @Override
        public boolean isEOF()
        {
            return true;
        }

        public String toString()
        {
            return "EOF";
        }
    };


    public void init(HttpChannelState state)
    {
        _channelState=state;
    }

}
