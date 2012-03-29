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

package org.eclipse.jetty.io.nio;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout.Task;

/* ------------------------------------------------------------ */
/**
 * An Endpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements EndPoint
{
    public static final Logger LOG=Log.getLogger("org.eclipse.jetty.io.nio");

    private final SelectorManager.SelectSet _selectSet;
    private final SelectorManager _manager;
    private  SelectionKey _key;

    /** The desired value for {@link SelectionKey#interestOps()} */
    private int _interestOps;

    /** true if {@link SelectSet#destroyEndPoint(SelectChannelEndPoint)} has not been called */
    private boolean _open;

    private volatile long _idleTimestamp;
    private volatile Connection _connection;
    
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
    public void setConnection(Connection connection)
    {
        Connection old=getConnection();
        _connection=connection;
        if (old!=null && old!=connection)
            _manager.endPointUpgraded(this,(Connection)old);
    }

    /* ------------------------------------------------------------ */
    public long getIdleTimestamp()
    {
        return _idleTimestamp;
    }

    /* ------------------------------------------------------------ */
    /** Called by selectSet to schedule handling
     *
     */
    public void selected()
    {
        final boolean can_read;
        final boolean can_write;
        synchronized (this)
        {
            // If there is no key, then do nothing
            if (_key == null || !_key.isValid())
            {
                this.notifyAll();
                return;
            }

            can_read=(_key.isReadable() && (_key.interestOps()|SelectionKey.OP_READ)!=0);
            can_write=(_key.isWritable() && (_key.interestOps()|SelectionKey.OP_WRITE)!=0);
            _interestOps=0;
            _key.interestOps(0);
        }
        
        if (can_read)
            getConnection().canRead();
        if (can_write)
            getConnection().canWrite();
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
    public void setCheckForIdle(boolean check)
    {
        _idleTimestamp=check?System.currentTimeMillis():0;
    }

    /* ------------------------------------------------------------ */
    public boolean isCheckForIdle()
    {
        return _idleTimestamp!=0;
    }

    /* ------------------------------------------------------------ */
    protected void notIdle()
    {
        if (_idleTimestamp!=0)
            _idleTimestamp=System.currentTimeMillis();
    }

    /* ------------------------------------------------------------ */
    public void checkIdleTimestamp(long now)
    {
        long idleTimestamp=_idleTimestamp;
        long max_idle_time=getMaxIdleTime();

        if (idleTimestamp!=0 && max_idle_time>0)
        {
            long idleForMs=now-idleTimestamp;

            if (idleForMs>max_idle_time)
            {
                onIdleExpired(idleForMs);
                _idleTimestamp=now;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void onIdleExpired(long idleForMs)
    {
        getConnection().onIdleExpired(idleForMs);
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
     * Updates selection key. Adds operations types to the selection key as needed. No operations
     * are removed as this is only done during dispatch. This method records the new key and
     * schedules a call to doUpdateKey to do the keyChange
     */
    public void updateKey()
    {
        final boolean changed;
        synchronized (this)
        {
            int current_ops=-1;
            if (getChannel().isOpen())
            {
                Socket socket = getSocket();
                boolean read_interest = getConnection().isReadInterested() && !socket.isInputShutdown();
                boolean write_interest= getConnection().isWriteInterested() && !socket.isOutputShutdown();

                _interestOps = (read_interest?SelectionKey.OP_READ:0)|(write_interest?SelectionKey.OP_WRITE:0);
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
            changed=_interestOps!=current_ops;
        }

        if(changed)
        {
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
        synchronized (this)
        {
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
                getConnection());
    }

    /* ------------------------------------------------------------ */
    public SelectSet getSelectSet()
    {
        return _selectSet;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Connection getConnection()
    {
        return _connection;
    }

}
