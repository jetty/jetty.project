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

import static org.eclipse.jetty.io.CompletedIOFuture.COMPLETE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.io.SelectorManager.SelectSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout.Task;

/* ------------------------------------------------------------ */
/**
 * An Endpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements AsyncEndPoint
{
    public static final Logger LOG=Log.getLogger(SelectChannelEndPoint.class);
    
    private final Lock _lock = new ReentrantLock();
    
    private final SelectorManager.SelectSet _selectSet;
    private final SelectorManager _manager;
    
    private DispatchedIOFuture _readFuture=new DispatchedIOFuture(true,_lock);
    private DispatchedIOFuture _writeFuture=new DispatchedIOFuture(true,_lock);
    
    private  SelectionKey _key;

    private boolean _selected;
    private boolean _changing;
    
    /** The desired value for {@link SelectionKey#interestOps()} */
    private int _interestOps;
    
    /** true if {@link SelectSet#destroyEndPoint(SelectChannelEndPoint)} has not been called */
    private boolean _open;

    private volatile boolean _idlecheck;
    private volatile AbstractAsyncConnection _connection;
    
    private ByteBuffer[] _writeBuffers;
    
    /* ------------------------------------------------------------ */
    public SelectChannelEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key, int maxIdleTime)
        throws IOException
    {
        super(channel);
        _manager = selectSet.getManager();
        _selectSet = selectSet;
        _open=true;
        _key = key;

        setMaxIdleTime(maxIdleTime);
        setCheckForIdle(true);
    }

    /* ------------------------------------------------------------ */
    public AbstractAsyncConnection getAsyncConnection()
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
    public SelectorManager getSelectManager()
    {
        return _manager;
    }


    /* ------------------------------------------------------------ */
    public void setAsyncConnection(AbstractAsyncConnection connection)
    {
        AbstractAsyncConnection old=getAsyncConnection();
        _connection=connection;
        if (old!=null && old!=connection)
            _manager.endPointUpgraded(this,old);
    }
    

    /* ------------------------------------------------------------ */
    /** Called by selectSet to schedule handling
     *
     */
    public void onSelected()
    {
        _lock.lock();
        _selected=true;
        try
        {
            // If there is no key, then do nothing
            if (_key == null || !_key.isValid())
            {
                // TODO wake ups?
                return;
            }
            
            //TODO do we need to test interest here ???
            boolean can_read=(_key.isReadable() && (_key.interestOps()&SelectionKey.OP_READ)==SelectionKey.OP_READ);
            boolean can_write=(_key.isWritable() && (_key.interestOps()&SelectionKey.OP_WRITE)==SelectionKey.OP_WRITE);
            _interestOps=0;

            if (can_read && !_readFuture.isComplete())
                _readFuture.ready();
            
            if (can_write && _writeBuffers!=null)
                completeWrite();
            
        }
        finally
        {
            doUpdateKey();
            _selected=false;
            _lock.unlock();
        }
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
        _idlecheck=check;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isCheckForIdle()
    {
        return _idlecheck;
    }


    /* ------------------------------------------------------------ */
    public void checkForIdleOrReadWriteTimeout(long now)
    {        
        if (_idlecheck || !_readFuture.isComplete() || !_writeFuture.isComplete())
        {
            long idleTimestamp=getIdleTimestamp();
            long max_idle_time=getMaxIdleTime();

            if (idleTimestamp!=0 && max_idle_time>0)
            {
                long idleForMs=now-idleTimestamp;

                if (idleForMs>max_idle_time)
                {
                    _lock.lock();
                    try
                    {
                        if (_idlecheck)
                            _connection.onIdleExpired(idleForMs);
                        if (!_readFuture.isComplete())
                            _readFuture.fail(new TimeoutException());
                        if (!_writeFuture.isComplete())
                            _writeFuture.fail(new TimeoutException());
                        notIdle();
                    }
                    finally
                    {
                        _lock.unlock();
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int fill=super.fill(buffer);
        if (fill>0)
            notIdle();
        return fill;
    }

    /* ------------------------------------------------------------ */
    @Override
    public IOFuture readable() throws IllegalStateException
    {
        _lock.lock();
        try
        {
            if (_readFuture!=null && !_readFuture.isComplete())
                throw new IllegalStateException("previous read not complete");
                
            _readFuture=new InterestedFuture(SelectionKey.OP_READ);
            _interestOps=_interestOps|SelectionKey.OP_READ;
            updateKey();
            
            return _readFuture;
        }
        finally
        {
            _lock.unlock();
        }
    }
    
   
    /* ------------------------------------------------------------ */
    @Override
    public IOFuture write(ByteBuffer... buffers)
    {
        _lock.lock();
        try
        {
            if (_writeFuture!=null && !_writeFuture.isComplete())
                throw new IllegalStateException("previous write not complete");

            flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    _writeBuffers=buffers;
                    _writeFuture=new InterestedFuture(SelectionKey.OP_WRITE);
                    _interestOps=_interestOps|SelectionKey.OP_WRITE;
                    updateKey();
                    return _writeFuture;
                }
            }
            return COMPLETE;
        }
        catch(IOException e)
        {
            return new CompletedIOFuture(e);
        } 
        finally
        {
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    private void completeWrite()
    {
        try
        {
            flush(_writeBuffers);
            
            // Are we complete?
            for (ByteBuffer b : _writeBuffers)
            {
                if (b.hasRemaining())
                {
                    _interestOps=_interestOps|SelectionKey.OP_WRITE;
                    return;
                }
            }
            // we are complete and ready
            _writeFuture.ready();
        }
        catch(final IOException e)
        {
            _writeBuffers=null;
            if (!_writeFuture.isComplete())
                _writeFuture.fail(e);
        }
        

    }

    
    
    /* ------------------------------------------------------------ */
    @Override
    public int flush(ByteBuffer... buffers) throws IOException
    {
        int l = super.flush(buffers);
        if (l>0)
            notIdle();
        return l;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Updates selection key. This method schedules a call to doUpdateKey to do the keyChange
     */
    private void updateKey()
    {
        if (!_lock.tryLock())
            throw new IllegalStateException();
        try
        {
            if (!_selected)
            {
                int current_ops=-1;
                if (getChannel().isOpen())
                {
                    try
                    {
                        current_ops = ((_key!=null && _key.isValid())?_key.interestOps():-1);
                    }
                    catch(Exception e)
                    {
                        _key=null;
                        LOG.ignore(e);
                    }
                }
                if (_interestOps!=current_ops && !_changing)
                {
                    _changing=true;
                    _selectSet.addChange(this);
                    _selectSet.wakeup();
                }
            }
        }
        finally
        {
            _lock.unlock();
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * Synchronize the interestOps with the actual key. Call is scheduled by a call to updateKey
     */
    void doUpdateKey()
    {
        _lock.lock();
        try
        {
            _changing=false;
            if (getChannel().isOpen())
            {
                if (_interestOps>0)
                {
                    if (_key==null || !_key.isValid())
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
                                _key=((SelectableChannel)getChannel()).register(_selectSet.getSelector(),_interestOps,this);
                            }
                            catch (Exception e)
                            {
                                LOG.ignore(e);
                                if (_key!=null && _key.isValid())
                                {
                                    _key.cancel();
                                }

                                if (_open)
                                {
                                    _selectSet.destroyEndPoint(this);
                                }
                                _open=false;
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
                    if (_key!=null && _key.isValid())
                        _key.interestOps(0);
                    else
                        _key=null;
                }
            }
            else
            {
                if (_key!=null && _key.isValid())
                    _key.cancel();

                if (_open)
                {
                    _open=false;
                    _selectSet.destroyEndPoint(this);
                }
                _key = null;
            }
        }
        finally
        {
            _lock.unlock();
        }
    }


    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.nio.ChannelEndPoint#close()
     */
    @Override
    public void close()
    {
        _lock.lock();
        try
        {
            try
            {
                super.close();
            }
            finally
            {
                updateKey();
            }
        }
        finally
        {
            _lock.unlock();
        }
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
        
        
        return String.format("SCEP@%x{l(%s)<->r(%s),open=%b,ishut=%b,oshut=%b,i=%d%s,r=%s,w=%s}-{%s}",
                hashCode(),
                getRemoteAddress(),
                getLocalAddress(),
                isOpen(),
                isInputShutdown(),
                isOutputShutdown(),
                _interestOps,
                keyString,
                _readFuture,
                _writeFuture,
                getAsyncConnection());
    }

    /* ------------------------------------------------------------ */
    public SelectSet getSelectSet()
    {
        return _selectSet;
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class InterestedFuture extends DispatchedIOFuture
    {
        final int _interest;
        private InterestedFuture(int interest)
        {
            super(_lock);
            _interest=interest;
        }

        @Override
        protected void dispatch(Runnable task)
        {
            if (!_manager.dispatch(task))
            {
                LOG.warn("Dispatch failed: i="+_interest);
                throw new IllegalStateException();
            }
        }

        @Override
        public void cancel()
        {
            _lock.lock();
            try
            {
                _interestOps=_interestOps&~_interest;
                updateKey();
                cancelled();
            }
            finally
            {
                _lock.unlock();
            }
        }
    }


}
