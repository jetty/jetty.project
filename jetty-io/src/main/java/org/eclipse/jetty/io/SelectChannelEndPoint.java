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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
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
public class SelectChannelEndPoint extends ChannelEndPoint implements SelectableEndPoint
{
    public static final Logger LOG=Log.getLogger(SelectChannelEndPoint.class);

    private final Lock _lock = new ReentrantLock();
    private final SelectorManager.SelectSet _selectSet;
    private final SelectorManager _manager;
    private  SelectionKey _key;

    private boolean _selected;
    private boolean _changing;
    
    /** The desired value for {@link SelectionKey#interestOps()} */
    private int _interestOps;

    private boolean _ishutCalled;
    
    /** true if {@link SelectSet#destroyEndPoint(SelectChannelEndPoint)} has not been called */
    private boolean _open;

    private volatile boolean _idlecheck;
    private volatile long _lastNotIdleTimestamp;
    private volatile SelectableConnection _connection;
    
    /* ------------------------------------------------------------ */
    public SelectChannelEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key, int maxIdleTime)
        throws IOException
    {
        super(channel, maxIdleTime);

        _manager = selectSet.getManager();
        _selectSet = selectSet;
        _open=true;
        _key = key;

        setCheckForIdle(true);
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
    public void setSelectableConnection(SelectableConnection connection)
    {
        Connection old=getSelectableConnection();
        _connection=connection;
        if (old!=null && old!=connection)
            _manager.endPointUpgraded(this,old);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public long getLastNotIdleTimestamp()
    {
        return _lastNotIdleTimestamp;
    }

    /* ------------------------------------------------------------ */
    /** Called by selectSet to schedule handling
     *
     */
    public void selected() throws IOException
    {
        _lock.lock();
        _selected=true;
        try
        {
            // If there is no key, then do nothing
            if (_key == null || !_key.isValid())
            {
                this.notifyAll();
                return;
            }
            
            boolean can_read=(_key.isReadable() && (_key.interestOps()|SelectionKey.OP_READ)!=0);
            boolean can_write=(_key.isWritable() && (_key.interestOps()|SelectionKey.OP_WRITE)!=0);
            _interestOps=0;

            if (can_read)
            {
                Runnable task=getSelectableConnection().onReadable();
                if (task!=null)
                    _manager.dispatch(task);
            }
            if (can_write)
            {
                Runnable task=getSelectableConnection().onWriteable();
                if (task!=null)
                    _manager.dispatch(task);
            }
            
            if (isInputShutdown() && !_ishutCalled)
            {
                _ishutCalled=true;
                getSelectableConnection().onInputShutdown();
            }
        }
        finally
        {
            doUpdateKey();
            _selected=false;
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isReadInterested()
    {
        _lock.lock();
        try
        {
            return (_interestOps&SelectionKey.OP_READ)!=0;
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void setReadInterested(boolean interested)
    {
        _lock.lock();
        try
        {
            _interestOps=interested?(_interestOps|SelectionKey.OP_READ):(_interestOps&~SelectionKey.OP_READ);
            if (!_selected)
                updateKey();
        }
        finally
        {
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isWriteInterested()
    {
        _lock.lock();
        try
        {
            return (_interestOps&SelectionKey.OP_READ)!=0;
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void setWriteInterested(boolean interested)
    {
        _lock.lock();
        try
        {
            _interestOps=interested?(_interestOps|SelectionKey.OP_WRITE):(_interestOps&~SelectionKey.OP_WRITE);
            if (!_selected)
                updateKey();
        }
        finally
        {
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
        _idlecheck=true;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isCheckForIdle()
    {
        return _idlecheck;
    }

    /* ------------------------------------------------------------ */
    protected void notIdle()
    {
        _lastNotIdleTimestamp=System.currentTimeMillis();
    }

    /* ------------------------------------------------------------ */
    public void checkForIdle(long now)
    {
        if (_idlecheck)
        {
            long idleTimestamp=_lastNotIdleTimestamp;
            long max_idle_time=getMaxIdleTime();

            if (idleTimestamp!=0 && max_idle_time>0)
            {
                long idleForMs=now-idleTimestamp;

                if (idleForMs>max_idle_time)
                {
                    onIdleExpired(idleForMs);
                    _lastNotIdleTimestamp=now;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onIdleExpired(long idleForMs)
    {
        getSelectableConnection().onIdleExpired(idleForMs);
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
    public void close() throws IOException
    {
        try
        {
            super.close();
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }
        finally
        {
            updateKey();
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
        
        
        return String.format("SCEP@%x{l(%s)<->r(%s),open=%b,ishut=%b,oshut=%b,i=%d%s}-{%s}",
                hashCode(),
                getRemoteAddress(),
                getLocalAddress(),
                isOpen(),
                isInputShutdown(),
                isOutputShutdown(),
                _interestOps,
                keyString,
                getSelectableConnection());
    }

    /* ------------------------------------------------------------ */
    public SelectSet getSelectSet()
    {
        return _selectSet;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public SelectableConnection getSelectableConnection()
    {
        return _connection;
    }


}
