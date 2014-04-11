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

package org.eclipse.jetty.io.nio;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Locale;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout.Task;

/* ------------------------------------------------------------ */
/**
 * An Endpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements AsyncEndPoint, ConnectedEndPoint
{
    public static final Logger LOG=Log.getLogger("org.eclipse.jetty.io.nio");

    private final boolean WORK_AROUND_JVM_BUG_6346658 = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win");
    private final SelectorManager.SelectSet _selectSet;
    private final SelectorManager _manager;
    private  SelectionKey _key;
    private final Runnable _handler = new Runnable()
        {
            public void run() { handle(); }
        };

    /** The desired value for {@link SelectionKey#interestOps()} */
    private int _interestOps;

    /**
     * The connection instance is the handler for any IO activity on the endpoint.
     * There is a different type of connection for HTTP, AJP, WebSocket and
     * ProxyConnect.   The connection may change for an SCEP as it is upgraded
     * from HTTP to proxy connect or websocket.
     */
    private volatile AsyncConnection _connection;

    private static final int STATE_NEEDS_DISPATCH=-1;
    private static final int STATE_UNDISPATCHED=0;
    private static final int STATE_DISPATCHED=1;
    private static final int STATE_ASYNC=2;
    private int _state;
    
    private boolean _onIdle;

    /** true if the last write operation succeed and wrote all offered bytes */
    private volatile boolean _writable = true;


    /** True if a thread has is blocked in {@link #blockReadable(long)} */
    private boolean _readBlocked;

    /** True if a thread has is blocked in {@link #blockWritable(long)} */
    private boolean _writeBlocked;

    /** true if {@link SelectSet#destroyEndPoint(SelectChannelEndPoint)} has not been called */
    private boolean _open;

    private volatile long _idleTimestamp;
    private volatile boolean _checkIdle;
    
    private boolean _interruptable;

    private boolean _ishut;

    /* ------------------------------------------------------------ */
    public SelectChannelEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key, int maxIdleTime)
        throws IOException
    {
        super(channel, maxIdleTime);

        _manager = selectSet.getManager();
        _selectSet = selectSet;
        _state=STATE_UNDISPATCHED;
        _onIdle=false;
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
    public Connection getConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    public void setConnection(Connection connection)
    {
        Connection old=_connection;
        _connection=(AsyncConnection)connection;
        if (old!=null && old!=_connection)
            _manager.endPointUpgraded(this,old);
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
    public void schedule()
    {
        synchronized (this)
        {
            // If there is no key, then do nothing
            if (_key == null || !_key.isValid())
            {
                _readBlocked=false;
                _writeBlocked=false;
                this.notifyAll();
                return;
            }

            // If there are threads dispatched reading and writing
            if (_readBlocked || _writeBlocked)
            {
                // assert _dispatched;
                if (_readBlocked && _key.isReadable())
                    _readBlocked=false;
                if (_writeBlocked && _key.isWritable())
                    _writeBlocked=false;

                // wake them up is as good as a dispatched.
                this.notifyAll();

                // we are not interested in further selecting
                _key.interestOps(0);
                if (_state<STATE_DISPATCHED)
                    updateKey();
                return;
            }

            // Remove writeable op
            if ((_key.readyOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE && (_key.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE)
            {
                // Remove writeable op
                _interestOps = _key.interestOps() & ~SelectionKey.OP_WRITE;
                _key.interestOps(_interestOps);
                _writable = true; // Once writable is in ops, only removed with dispatch.
            }

            // If dispatched, then deregister interest
            if (_state>=STATE_DISPATCHED)
                _key.interestOps(0);
            else
            {
                // other wise do the dispatch
                dispatch();
                if (_state>=STATE_DISPATCHED && !_selectSet.getManager().isDeferringInterestedOps0())
                {
                    _key.interestOps(0);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void asyncDispatch()
    {
        synchronized(this)
        {
            switch(_state)
            {
                case STATE_NEEDS_DISPATCH:
                case STATE_UNDISPATCHED:
                    dispatch();
                    break;
                    
                case STATE_DISPATCHED:
                case STATE_ASYNC:
                    _state=STATE_ASYNC;
                    break;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void dispatch()
    {
        synchronized(this)
        {
            if (_state<=STATE_UNDISPATCHED)
            {
                if (_onIdle)
                    _state = STATE_NEEDS_DISPATCH;
                else
                {
                    _state = STATE_DISPATCHED;
                    boolean dispatched = _manager.dispatch(_handler);
                    if(!dispatched)
                    {
                        _state = STATE_NEEDS_DISPATCH;
                        LOG.warn("Dispatched Failed! "+this+" to "+_manager);
                        updateKey();
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Called when a dispatched thread is no longer handling the endpoint.
     * The selection key operations are updated.
     * @return If false is returned, the endpoint has been redispatched and
     * thread must keep handling the endpoint.
     */
    protected boolean undispatch()
    {
        synchronized (this)
        {
            switch(_state)
            {
                case STATE_ASYNC:
                    _state=STATE_DISPATCHED;
                    return false;

                default:
                    _state=STATE_UNDISPATCHED;
                    updateKey();
                    return true;
            }
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
    public void setCheckForIdle(boolean check)
    {
        if (check)
        {
            _idleTimestamp=System.currentTimeMillis();
            _checkIdle=true;
        }
        else
            _checkIdle=false;
    }

    /* ------------------------------------------------------------ */
    public boolean isCheckForIdle()
    {
        return _checkIdle;
    }

    /* ------------------------------------------------------------ */
    protected void notIdle()
    {
        _idleTimestamp=System.currentTimeMillis();
    }

    /* ------------------------------------------------------------ */
    public void checkIdleTimestamp(long now)
    {
        if (isCheckForIdle() && _maxIdleTime>0)
        {
            final long idleForMs=now-_idleTimestamp;

            if (idleForMs>_maxIdleTime)
            {
                // Don't idle out again until onIdleExpired task completes.
                setCheckForIdle(false);
                _manager.dispatch(new Runnable()
                {
                    public void run()
                    {
                        try
                        {
                            onIdleExpired(idleForMs);
                        }
                        finally
                        {
                            setCheckForIdle(true);
                        }
                    }
                });
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void onIdleExpired(long idleForMs)
    {
        try
        {
            synchronized (this)
            {
                _onIdle=true;
            }

            _connection.onIdleExpired(idleForMs);
        }
        finally
        {
            synchronized (this)
            {
                _onIdle=false;
                if (_state==STATE_NEEDS_DISPATCH)
                    dispatch();
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public int fill(Buffer buffer) throws IOException
    {
        int fill=super.fill(buffer);
        if (fill>0)
            notIdle();
        return fill;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        int l = super.flush(header, buffer, trailer);

        // If there was something to write and it wasn't written, then we are not writable.
        if (l==0 && ( header!=null && header.hasContent() || buffer!=null && buffer.hasContent() || trailer!=null && trailer.hasContent()))
        {
            synchronized (this)
            {   
                _writable=false;
                if (_state<STATE_DISPATCHED)
                    updateKey();
            }
        }
        else if (l>0)
        {
            _writable=true;
            notIdle();
        }
        return l;
    }

    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public int flush(Buffer buffer) throws IOException
    {
        int l = super.flush(buffer);

        // If there was something to write and it wasn't written, then we are not writable.
        if (l==0 && buffer!=null && buffer.hasContent())
        {
            synchronized (this)
            {   
                _writable=false;
                if (_state<STATE_DISPATCHED)
                    updateKey();
            }
        }
        else if (l>0)
        {
            _writable=true;
            notIdle();
        }

        return l;
    }

    /* ------------------------------------------------------------ */
    /*
     * Allows thread to block waiting for further events.
     */
    @Override
    public boolean blockReadable(long timeoutMs) throws IOException
    {
        synchronized (this)
        {
            if (isInputShutdown())
                throw new EofException();

            long now=_selectSet.getNow();
            long end=now+timeoutMs;
            boolean check=isCheckForIdle();
            setCheckForIdle(true);
            try
            {
                _readBlocked=true;
                while (!isInputShutdown() && _readBlocked)
                {
                    try
                    {
                        updateKey();
                        this.wait(timeoutMs>0?(end-now):10000);
                    }
                    catch (final InterruptedException e)
                    {
                        LOG.warn(e);
                        if (_interruptable)
                            throw new InterruptedIOException(){{this.initCause(e);}};
                    }
                    finally
                    {
                        now=_selectSet.getNow();
                    }

                    if (_readBlocked && timeoutMs>0 && now>=end)
                        return false;
                }
            }
            finally
            {
                _readBlocked=false;
                setCheckForIdle(check);
            }
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /*
     * Allows thread to block waiting for further events.
     */
    @Override
    public boolean blockWritable(long timeoutMs) throws IOException
    {
        synchronized (this)
        {
            if (isOutputShutdown())
                throw new EofException();

            long now=_selectSet.getNow();
            long end=now+timeoutMs;
            boolean check=isCheckForIdle();
            setCheckForIdle(true);
            try
            {
                _writeBlocked=true;
                while (_writeBlocked && !isOutputShutdown())
                {
                    try
                    {
                        updateKey();
                        this.wait(timeoutMs>0?(end-now):10000);
                    }
                    catch (final InterruptedException e)
                    {
                        LOG.warn(e);
                        if (_interruptable)
                            throw new InterruptedIOException(){{this.initCause(e);}};
                    }
                    finally
                    {
                        now=_selectSet.getNow();
                    }
                    if (_writeBlocked && timeoutMs>0 && now>=end)
                        return false;
                }
            }
            finally
            {
                _writeBlocked=false;
                setCheckForIdle(check);
            }
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /** Set the interruptable mode of the endpoint.
     * If set to false (default), then interrupts are assumed to be spurious 
     * and blocking operations continue unless the endpoint has been closed.
     * If true, then interrupts of blocking operations result in InterruptedIOExceptions
     * being thrown.
     * @param interupable
     */
    public void setInterruptable(boolean interupable)
    {
        synchronized (this)
        {
            _interruptable=interupable;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isInterruptable()
    {
        return _interruptable;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.AsyncEndPoint#scheduleWrite()
     */
    public void scheduleWrite()
    {
        if (_writable)
            LOG.debug("Required scheduleWrite {}",this);

        _writable=false;
        updateKey();
    }

    /* ------------------------------------------------------------ */
    public boolean isWritable()
    {
        return _writable;
    }

    /* ------------------------------------------------------------ */
    public boolean hasProgressed()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Updates selection key. Adds operations types to the selection key as needed. No operations
     * are removed as this is only done during dispatch. This method records the new key and
     * schedules a call to doUpdateKey to do the keyChange
     */
    private void updateKey()
    {
        final boolean changed;
        synchronized (this)
        {
            int current_ops=-1;
            if (getChannel().isOpen())
            {
                boolean read_interest = _readBlocked || (_state<STATE_DISPATCHED && !_connection.isSuspended());
                boolean write_interest= _writeBlocked || (_state<STATE_DISPATCHED && !_writable);

                _interestOps =
                    ((!_socket.isInputShutdown() && read_interest ) ? SelectionKey.OP_READ  : 0)
                |   ((!_socket.isOutputShutdown()&& write_interest) ? SelectionKey.OP_WRITE : 0);
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
     */
    protected void handle()
    {
        boolean dispatched=true;
        try
        {
            while(dispatched)
            {
                try
                {
                    while(true)
                    {
                        final AsyncConnection next = (AsyncConnection)_connection.handle();
                        if (next!=_connection)
                        {
                            LOG.debug("{} replaced {}",next,_connection);
                            Connection old=_connection;
                            _connection=next;
                            _manager.endPointUpgraded(this,old);
                            continue;
                        }
                        break;
                    }
                }
                catch (ClosedChannelException e)
                {
                    LOG.ignore(e);
                }
                catch (EofException e)
                {
                    LOG.debug("EOF", e);
                    try{close();}
                    catch(IOException e2){LOG.ignore(e2);}
                }
                catch (IOException e)
                {
                    LOG.warn(e.toString());
                    try{close();}
                    catch(IOException e2){LOG.ignore(e2);}
                }
                catch (Throwable e)
                {
                    LOG.warn("handle failed", e);
                    try{close();}
                    catch(IOException e2){LOG.ignore(e2);}
                }
                finally
                {
                    if (!_ishut && isInputShutdown() && isOpen())
                    {
                        _ishut=true;
                        try
                        {
                            _connection.onInputShutdown();
                        }
                        catch(Throwable x)
                        {
                            LOG.warn("onInputShutdown failed", x);
                            try{close();}
                            catch(IOException e2){LOG.ignore(e2);}
                        }
                        finally
                        {
                            updateKey();
                        }
                    }
                    dispatched=!undispatch();
                }
            }
        }
        finally
        {
            if (dispatched)
            {
                dispatched=!undispatch();
                while (dispatched)
                {
                    LOG.warn("SCEP.run() finally DISPATCHED");
                    dispatched=!undispatch();
                }
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
        // On unix systems there is a JVM issue that if you cancel before closing, it can 
        // cause the selector to block waiting for a channel to close and that channel can 
        // block waiting for the remote end.  But on windows, if you don't cancel before a 
        // close, then the selector can block anyway!
        // https://bugs.eclipse.org/bugs/show_bug.cgi?id=357318
        if (WORK_AROUND_JVM_BUG_6346658)
        {
            try
            {
                SelectionKey key = _key;
                if (key!=null)
                    key.cancel();
            }
            catch (Throwable e)
            {
                LOG.ignore(e);
            }
        }

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
        return String.format("SCEP@%x{l(%s)<->r(%s),s=%d,open=%b,ishut=%b,oshut=%b,rb=%b,wb=%b,w=%b,i=%d%s}-{%s}",
                hashCode(),
                _socket.getRemoteSocketAddress(),
                _socket.getLocalSocketAddress(),
                _state,
                isOpen(),
                isInputShutdown(),
                isOutputShutdown(),
                _readBlocked,
                _writeBlocked,
                _writable,
                _interestOps,
                keyString,
                _connection);
    }

    /* ------------------------------------------------------------ */
    public SelectSet getSelectSet()
    {
        return _selectSet;
    }

    /* ------------------------------------------------------------ */
    /**
     * Don't set the SoTimeout
     * @see org.eclipse.jetty.io.nio.ChannelEndPoint#setMaxIdleTime(int)
     */
    @Override
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        _maxIdleTime=timeMs;
    }

}
