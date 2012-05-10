// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.io.SelectorManager.SelectSet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout.Task;

/* ------------------------------------------------------------ */
/**
 * An Endpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements SelectorManager.SelectableAsyncEndPoint
{
    public static final Logger LOG = Log.getLogger(SelectChannelEndPoint.class);

    private final Object _lock = this;

    private final SelectorManager.SelectSet _selectSet;
    private final SelectorManager _manager;

    private SelectionKey _key;

    private boolean _selected;
    private boolean _changing;

    /** The desired value for {@link SelectionKey#interestOps()} */
    private int _interestOps;

    /** true if {@link SelectSet#destroyEndPoint(SelectChannelEndPoint)} has not been called */
    private boolean _open;

    private volatile boolean _idlecheck;
    private volatile AsyncConnection _connection;

    private final ReadInterest _readInterest = new ReadInterest()
    {
        @Override
        protected boolean makeInterested()
        {
            _interestOps=_interestOps | SelectionKey.OP_READ;
            updateKey();
            return false;
        }
    };
    
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void scheduleCompleteWrite()
        {
            _interestOps = _interestOps | SelectionKey.OP_WRITE;
            updateKey();
        }
    };

    /* ------------------------------------------------------------ */
    public SelectChannelEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key, int maxIdleTime) throws IOException
    {
        super(channel);
        _manager = selectSet.getManager();
        _selectSet = selectSet;
        _open = true;
        _key = key;

        setMaxIdleTime(maxIdleTime);
        setCheckForIdle(true);
    }

    /* ------------------------------------------------------------ */
    @Override
    public AsyncConnection getAsyncConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    public SelectionKey getSelectionKey()
    {
        synchronized (this)
        {
            return _key;
        }
    }

    /* ------------------------------------------------------------ */
    public SelectorManager getSelectorManager()
    {
        return _manager;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setAsyncConnection(AsyncConnection connection)
    {
        AsyncConnection old = getAsyncConnection();
        _connection = connection;
        if (old != null && old != connection)
            _manager.endPointUpgraded(this,old);
    }

    /* ------------------------------------------------------------ */
    /**
     * Called by selectSet to schedule handling
     * 
     */
    @Override
    public void onSelected()
    {
        boolean can_read;
        boolean can_write;
        
        synchronized (_lock)
        {
            _selected = true;
            try
            {
                // If there is no key, then do nothing
                if (_key == null || !_key.isValid())
                    return;

                can_read = (_key.isReadable() && (_key.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ);
                can_write = (_key.isWritable() && (_key.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE);
                _interestOps = 0;
            }
            finally
            {
                doUpdateKey();
                _selected = false;
            }
        }
        if (can_read)
            _readInterest.completed();
        if (can_write)
            _writeFlusher.completeWrite();
    }

    /* ------------------------------------------------------------ */
    public void cancelTimeout(Task task)
    {
        getSelectSet().cancelTimeout(task);
    }

    /* ------------------------------------------------------------ */
    public void scheduleTimeout(Task task, long timeoutMs)
    {
        getSelectSet().scheduleTimeout(task,timeoutMs);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setCheckForIdle(boolean check)
    {
        _idlecheck = check;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isCheckForIdle()
    {
        return _idlecheck;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void checkForIdleOrReadWriteTimeout(long now)
    {
        synchronized (_lock)
        {
            if (_idlecheck || _readInterest.isInterested() || _writeFlusher.isWriting())
            {
                long idleTimestamp = getIdleTimestamp();
                long max_idle_time = getMaxIdleTime();

                if (idleTimestamp != 0 && max_idle_time > 0)
                {
                    long idleForMs = now - idleTimestamp;

                    if (idleForMs > max_idle_time)
                    {
                        notIdle();
                        
                        if (_idlecheck)
                            _connection.onIdleExpired(idleForMs);
                        
                        TimeoutException timeout = new TimeoutException();
                        _readInterest.failed(timeout);
                        _writeFlusher.failWrite(timeout);
                    }
                }
            }
        }
    }
    

    /* ------------------------------------------------------------ */
    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int fill = super.fill(buffer);
        if (fill > 0)
            notIdle();
        return fill;
    }

    /* ------------------------------------------------------------ */
    @Override    
    public <C> void readable(C context, Callback<C> callback) throws IllegalStateException
    {
        _readInterest.readable(context,callback);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        _writeFlusher.write(context,callback,buffers);
    }

    /* ------------------------------------------------------------ */
    @Override
    public int flush(ByteBuffer... buffers) throws IOException
    {
        int l = super.flush(buffers);
        if (l > 0)
            notIdle();
        return l;
    }

    /* ------------------------------------------------------------ */
    /**
     * Updates selection key. This method schedules a call to doUpdateKey to do the keyChange
     */
    private void updateKey()
    {
        synchronized (_lock)
        {
            if (!_selected)
            {
                int current_ops = -1;
                if (getChannel().isOpen())
                {
                    try
                    {
                        current_ops = ((_key != null && _key.isValid())?_key.interestOps():-1);
                    }
                    catch (Exception e)
                    {
                        _key = null;
                        LOG.ignore(e);
                    }
                }
                if (_interestOps != current_ops && !_changing)
                {
                    _changing = true;
                    _selectSet.addChange(this);
                    _selectSet.wakeup();
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Synchronize the interestOps with the actual key. Call is scheduled by a call to updateKey
     */
    @Override
    public void doUpdateKey()
    {
        synchronized (_lock)
        {
            _changing = false;
            if (getChannel().isOpen())
            {
                if (_interestOps > 0)
                {
                    if (_key == null || !_key.isValid())
                    {
                        SelectableChannel sc = (SelectableChannel)getChannel();
                        if (sc.isRegistered())
                        {
                            updateKey();
                        }
                        else
                        {
                            try
                            {
                                _key = ((SelectableChannel)getChannel()).register(_selectSet.getSelector(),_interestOps,this);
                            }
                            catch (Exception e)
                            {
                                LOG.ignore(e);
                                if (_key != null && _key.isValid())
                                {
                                    _key.cancel();
                                }

                                if (_open)
                                {
                                    _selectSet.destroyEndPoint(this);
                                }
                                _open = false;
                                _key = null;
                            }
                        }
                    }
                    else
                    {
                        _key.interestOps(_interestOps);
                    }
                }
                else
                {
                    if (_key != null && _key.isValid())
                        _key.interestOps(0);
                    else
                        _key = null;
                }
            }
            else
            {
                if (_key != null && _key.isValid())
                    _key.cancel();

                if (_open)
                {
                    _open = false;
                    _selectSet.destroyEndPoint(this);
                }
                _key = null;
            }

        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.nio.ChannelEndPoint#close()
     */
    @Override
    public void close()
    {
        super.close();
        updateKey();
    }
    
    /* ------------------------------------------------------------ */
    @Override 
    public void onClose()
    {
        _writeFlusher.close();
        _readInterest.close();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        // Do NOT use synchronized (this)
        // because it's very easy to deadlock when debugging is enabled.
        // We do a best effort to print the right toString() and that's it.
        SelectionKey key = _key;
        String keyString = "";
        if (key != null)
        {
            if (key.isValid())
            {
                if (key.isReadable())
                    keyString += "r";
                if (key.isWritable())
                    keyString += "w";
            }
            else
            {
                keyString += "!";
            }
        }
        else
        {
            keyString += "-";
        }

        return String.format("SCEP@%x{l(%s)<->r(%s),open=%b,ishut=%b,oshut=%b,i=%d%s,r=%s,w=%s}-{%s}",hashCode(),getRemoteAddress(),getLocalAddress(),isOpen(),
                isInputShutdown(),isOutputShutdown(),_interestOps,keyString,_readInterest,_writeFlusher,getAsyncConnection());
    }

    /* ------------------------------------------------------------ */
    public SelectSet getSelectSet()
    {
        return _selectSet;
    }

}
