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

package org.eclipse.jetty.server;

import java.io.IOException;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link HttpInput} provides an implementation of {@link ServletInputStream} for {@link HttpChannel}.</p>
 */
public abstract class HttpInput<T> extends ServletInputStream implements Runnable
{
    private final static Logger LOG = Log.getLogger(HttpInput.class);

    private final byte[] _oneByteBuffer = new byte[1];
    private final Object _lock;
    private HttpChannelState _channelState;
    private ReadListener _listener;
    private Throwable _onError;
    private boolean _notReady;
    private State _state = BLOCKING;
    private State _eof;
    private long _contentRead;

    protected HttpInput()
    {
        this(null);
    }

    protected HttpInput(Object lock)
    {
        _lock = lock == null ? this : lock;
    }

    public void init(HttpChannelState state)
    {
        synchronized (lock())
        {
            _channelState = state;
        }
    }

    public final Object lock()
    {
        return _lock;
    }

    public void recycle()
    {
        synchronized (lock())
        {
            _state = BLOCKING;
            _eof=null;
            _onError=null;
            _contentRead=0;
        }
    }

    @Override
    public int available()
    {
        try
        {
            synchronized (lock())
            {
                T item = getNextContent();
                return item==null?0:remaining(item);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        return read < 0 ? -1 : 0xff & _oneByteBuffer[0];
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        T item = null;
        int l;
        synchronized (lock())
        {
            // System.err.printf("read s=%s q=%d e=%s%n",_state,_inputQ.size(),_eof);

            // Get the current head of the input Q
            item = getNextContent();

            // If we have no item
            if (item == null)
            {
                _state.waitForContent(this);
                item=getNextContent();
                if (item==null)
                    return _state.noContent();
            }
            
            l=get(item, b, off, len);
            _contentRead+=l;
            
        }
        return l;
    }
    
    /**
     * A convenience method to call nextContent and to check the return value, which if null then the
     * a check is made for EOF and the state changed accordingly.
     * @see #nextContent()
     * @return Content or null if none available.
     * @throws IOException
     */
    protected T getNextContent() throws IOException
    {
        T content=nextContent();

        if (content==null && _eof!=null)
        {
            LOG.debug("{} eof {}",this,_eof);
            _state=_eof;
            _eof=null;
        }

        return content;
    }

    /**
     * Access the next content to be consumed from.   Returning the next item does not consume it
     * and it may be returned multiple times until it is consumed.   Calls to {@link #get(Object, byte[], int, int)}
     * or {@link #consume(Object, int)} are required to consume data from the content.
     * @return Content or null if none available.
     * @throws IOException
     */
    protected abstract T nextContent() throws IOException;

    protected abstract int remaining(T item);

    protected abstract int get(T item, byte[] buffer, int offset, int length);

    protected abstract void consume(T item, int length);

    protected abstract void blockForContent() throws IOException;
    
    /** Add some content to the input stream
     * @param item
     */
    public abstract void content(T item);

    protected boolean onAsyncRead()
    {
        if (_listener == null)
            return false;
        _channelState.onReadPossible();
        return true;
    }

    public long getContentRead()
    {
        synchronized (lock())
        {
            return _contentRead;
        }
    }


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

    public void consumeAll()
    {
        synchronized (lock())
        {
            try
            {
                while (!isFinished())
                {
                    T item = getNextContent();
                    if (item==null)
                        _state.waitForContent(this);
                    else
                        consume(item,remaining(item));
                }
            }
            catch (IOException e)
            {
                LOG.debug(e);
            }
        }
    }

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
                item = getNextContent();
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
                _listener.onError(x);
            else if (available)
                _listener.onDataAvailable();
            else if (eof)
                _listener.onAllDataRead();
            else
                unready();
        }
        catch(Throwable e)
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
}
