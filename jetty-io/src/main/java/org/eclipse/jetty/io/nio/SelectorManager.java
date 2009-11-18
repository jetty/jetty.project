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
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;


/* ------------------------------------------------------------ */
/**
 * The Selector Manager manages and number of SelectSets to allow
 * NIO scheduling to scale to large numbers of connections.
 * 
 * 
 *
 */
public abstract class SelectorManager extends AbstractLifeCycle
{
    // TODO Tune these by approx system speed.
    private static final int __JVMBUG_THRESHHOLD=Integer.getInteger("org.mortbay.io.nio.JVMBUG_THRESHHOLD",512).intValue();
    private static final int __MONITOR_PERIOD=Integer.getInteger("org.mortbay.io.nio.MONITOR_PERIOD",1000).intValue();
    private static final int __MAX_SELECTS=Integer.getInteger("org.mortbay.io.nio.MAX_SELECTS",15000).intValue();
    private static final int __BUSY_PAUSE=Integer.getInteger("org.mortbay.io.nio.BUSY_PAUSE",50).intValue();
    private static final int __BUSY_KEY=Integer.getInteger("org.mortbay.io.nio.BUSY_KEY",-1).intValue();
    
    private long _maxIdleTime;
    private long _lowResourcesConnections;
    private long _lowResourcesMaxIdleTime;
    private transient SelectSet[] _selectSet;
    private int _selectSets=1;
    private volatile int _set;
    
    /* ------------------------------------------------------------ */
    /**
     * @param maxIdleTime The maximum period in milli seconds that a connection may be idle before it is closed.
     * @see {@link #setLowResourcesMaxIdleTime(long)}
     */
    public void setMaxIdleTime(long maxIdleTime)
    {
        _maxIdleTime=maxIdleTime;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param selectSets number of select sets to create
     */
    public void setSelectSets(int selectSets)
    {
        long lrc = _lowResourcesConnections * _selectSets; 
        _selectSets=selectSets;
        _lowResourcesConnections=lrc/_selectSets;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public long getMaxIdleTime()
    {
        return _maxIdleTime;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public int getSelectSets()
    {
        return _selectSets;
    }
    
    /* ------------------------------------------------------------ */
    /** Register a channel
     * @param channel
     * @param att Attached Object
     * @throws IOException
     */
    public void register(SocketChannel channel, Object att)
    {
        int s=_set++; 
        s=s%_selectSets;
        SelectSet[] sets=_selectSet;
        if (sets!=null)
        {
            SelectSet set=sets[s];
            set.addChange(channel,att);
            set.wakeup();
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Register a serverchannel
     * @param acceptChannel
     * @return
     * @throws IOException
     */
    public void register(ServerSocketChannel acceptChannel)
    {
        int s=_set++; 
        s=s%_selectSets;
        SelectSet set=_selectSet[s];
        set.addChange(acceptChannel);
        set.wakeup();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the lowResourcesConnections
     */
    public long getLowResourcesConnections()
    {
        return _lowResourcesConnections*_selectSets;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the number of connections, which if exceeded places this manager in low resources state.
     * This is not an exact measure as the connection count is averaged over the select sets.
     * @param lowResourcesConnections the number of connections
     * @see {@link #setLowResourcesMaxIdleTime(long)}
     */
    public void setLowResourcesConnections(long lowResourcesConnections)
    {
        _lowResourcesConnections=(lowResourcesConnections+_selectSets-1)/_selectSets;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the lowResourcesMaxIdleTime
     */
    public long getLowResourcesMaxIdleTime()
    {
        return _lowResourcesMaxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param lowResourcesMaxIdleTime the period in ms that a connection is allowed to be idle when this SelectSet has more connections than {@link #getLowResourcesConnections()}
     * @see {@link #setMaxIdleTime(long)}
     */
    public void setLowResourcesMaxIdleTime(long lowResourcesMaxIdleTime)
    {
        _lowResourcesMaxIdleTime=lowResourcesMaxIdleTime;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param acceptorID
     * @throws IOException
     */
    public void doSelect(int acceptorID) throws IOException
    {
        SelectSet[] sets= _selectSet;
        if (sets!=null && sets.length>acceptorID && sets[acceptorID]!=null)
            sets[acceptorID].doSelect();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param key
     * @return
     * @throws IOException 
     */
    protected abstract SocketChannel acceptChannel(SelectionKey key) throws IOException;

    /* ------------------------------------------------------------------------------- */
    public abstract boolean dispatch(Runnable task);

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _selectSet = new SelectSet[_selectSets];
        for (int i=0;i<_selectSet.length;i++)
            _selectSet[i]= new SelectSet(i);

        super.doStart();
    }


    /* ------------------------------------------------------------------------------- */
    @Override
    protected void doStop() throws Exception
    {
        SelectSet[] sets= _selectSet;
        _selectSet=null;
        if (sets!=null)
        {
            for (SelectSet set : sets)
            {
                if (set!=null)
                    set.stop();
            }
        }
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param endpoint
     */
    protected abstract void endPointClosed(SelectChannelEndPoint endpoint);

    /* ------------------------------------------------------------ */
    /**
     * @param endpoint
     */
    protected abstract void endPointOpened(SelectChannelEndPoint endpoint);

    /* ------------------------------------------------------------ */
    protected abstract void endPointUpgraded(ConnectedEndPoint endpoint,Connection oldConnection);

    /* ------------------------------------------------------------------------------- */
    protected abstract Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint);

    /* ------------------------------------------------------------ */
    /**
     * @param channel
     * @param selectSet
     * @param sKey
     * @return
     * @throws IOException
     */
    protected abstract SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey sKey) throws IOException;

    /* ------------------------------------------------------------------------------- */
    protected void connectionFailed(SocketChannel channel,Throwable ex,Object attachment)
    {
        Log.warn(ex+","+channel+","+attachment);
        Log.debug(ex);
    }

    /* ------------------------------------------------------------------------------- */
    public void dump()
    {
        for (final SelectSet set :_selectSet)
        {
            Thread selecting = set._selecting;
            Log.info("SelectSet "+set._setID+" : "+selecting);
            if (selecting!=null)
            {
                StackTraceElement[] trace =selecting.getStackTrace();
                if (trace!=null)
                {
                    for (StackTraceElement e : trace)
                    {
                        Log.info("\tat "+e.toString());
                    }
                }
            }
                
            set.addChange(new ChangeTask(){
                public void run()
                {
                    set.dump();
                }
            });
        }
    }
    
    
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    public class SelectSet 
    {
        private final int _setID;
        private final Timeout _idleTimeout;
        private final Timeout _timeout;
        private final List<Object>[] _changes;

        private int _change;
        private int _nextSet;
        private Selector _selector;
        private volatile Thread _selecting;
        private int _jvmBug;
        private int _selects;
        private long _monitorStart;
        private long _monitorNext;
        private boolean _pausing;
        private SelectionKey _busyKey;
        private int _busyKeyCount;
        private long _log;
        private int _paused;
        private int _jvmFix0;
        private int _jvmFix1;
        private int _jvmFix2;
        
        /* ------------------------------------------------------------ */
        SelectSet(int acceptorID) throws Exception
        {
            _setID=acceptorID;

            _idleTimeout = new Timeout(this);
            _idleTimeout.setDuration(getMaxIdleTime());
            _timeout = new Timeout(this);
            _timeout.setDuration(0L);
            _changes = new List[] {new ArrayList(),new ArrayList()};

            // create a selector;
            _selector = Selector.open();
            _change=0;
            _monitorStart=System.currentTimeMillis();
            _monitorNext=_monitorStart+__MONITOR_PERIOD;
            _log=_monitorStart+60000;
        }
        
        /* ------------------------------------------------------------ */
        public void addChange(Object point)
        {
            synchronized (_changes)
            {
                _changes[_change].add(point);
            }
        }
        
        /* ------------------------------------------------------------ */
        public void addChange(SelectableChannel channel, Object att)
        {   
            if (att==null)
                addChange(channel);
            else if (att instanceof EndPoint)
                addChange(att);
            else
                addChange(new ChangeSelectableChannel(channel,att));
        }
        
        /* ------------------------------------------------------------ */
        public void cancelIdle(Timeout.Task task)
        {
            task.cancel();
        }

        /* ------------------------------------------------------------ */
        /**
         * Select and dispatch tasks found from changes and the selector.
         * 
         * @throws IOException
         */
        public void doSelect() throws IOException
        {
            try
            {
                _selecting=Thread.currentThread();
                List<?> changes;
                final Selector selector;
                synchronized (_changes)
                {
                    changes=_changes[_change];
                    _change=_change==0?1:0;
                    selector=_selector;
                }

                // Make any key changes required
                final int size=changes.size();
                for (int i = 0; i < size; i++)
                {
                    try
                    {
                        Object o = changes.get(i);
                        
                        if (o instanceof EndPoint)
                        {
                            // Update the operations for a key.
                            SelectChannelEndPoint endpoint = (SelectChannelEndPoint)o;
                            endpoint.doUpdateKey();
                        }
                        else if (o instanceof Runnable)
                        {
                            dispatch((Runnable)o);
                        }
                        else if (o instanceof ChangeSelectableChannel)
                        {
                            // finish accepting/connecting this connection
                            final ChangeSelectableChannel asc = (ChangeSelectableChannel)o;
                            final SelectableChannel channel=asc._channel;
                            final Object att = asc._attachment;

                            if ((channel instanceof SocketChannel) && ((SocketChannel)channel).isConnected())
                            {
                                SelectionKey key = channel.register(selector,SelectionKey.OP_READ,att);
                                SelectChannelEndPoint endpoint = newEndPoint((SocketChannel)channel,this,key);
                                key.attach(endpoint);
                                endpoint.schedule();
                            }
                            else if (channel.isOpen())
                            {
                                channel.register(selector,SelectionKey.OP_CONNECT,att);
                            }
                        }
                        else if (o instanceof SocketChannel)
                        {
                            final SocketChannel channel=(SocketChannel)o;

                            if (channel.isConnected())
                            {
                                SelectionKey key = channel.register(selector,SelectionKey.OP_READ,null);
                                SelectChannelEndPoint endpoint = newEndPoint(channel,this,key);
                                key.attach(endpoint);
                                endpoint.schedule();
                            }
                            else if (channel.isOpen())
                            {
                                channel.register(selector,SelectionKey.OP_CONNECT,null);
                            }
                        }
                        else if (o instanceof ServerSocketChannel)
                        {
                            ServerSocketChannel channel = (ServerSocketChannel)o;
                            channel.register(getSelector(),SelectionKey.OP_ACCEPT);
                        }
                        else if (o instanceof ChangeTask)
                        {
                            ((ChangeTask)o).run();
                        }
                        else
                            throw new IllegalArgumentException(o.toString());
                    }
                    catch (Exception e)
                    {
                        if (isRunning())
                            Log.warn(e);
                        else
                            Log.debug(e);
                    }
                }
                changes.clear();

                long idle_next;
                long retry_next;
                long now=System.currentTimeMillis();
                synchronized (this)
                {
                    _idleTimeout.setNow(now);
                    _timeout.setNow(now);
                    
                    if (_lowResourcesConnections>0 && selector.keys().size()>_lowResourcesConnections)
                        _idleTimeout.setDuration(_lowResourcesMaxIdleTime);
                    else 
                        _idleTimeout.setDuration(_maxIdleTime);
                    idle_next=_idleTimeout.getTimeToNext();
                    retry_next=_timeout.getTimeToNext();
                }

                // workout how low to wait in select
                long wait = 1000L;  // not getMaxIdleTime() as the now value of the idle timers needs to be updated.
                if (idle_next >= 0 && wait > idle_next)
                    wait = idle_next;
                if (wait > 0 && retry_next >= 0 && wait > retry_next)
                    wait = retry_next;
    
                // Do the select.
                if (wait > 0) 
                {
                    // If we are in pausing mode
                    if (_pausing)
                    {
                        try
                        {
                            Thread.sleep(__BUSY_PAUSE); // pause to reduce impact of  busy loop
                        }
                        catch(InterruptedException e)
                        {
                            Log.ignore(e);
                        }
                    }
                        
                    long before=now;
                    int selected=selector.select(wait);
                    now = System.currentTimeMillis();
                    _idleTimeout.setNow(now);
                    _timeout.setNow(now);
                    _selects++;
  
                    // Look for JVM bugs over a monitor period.
                    // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6403933
                    // http://bugs.sun.com/view_bug.do?bug_id=6693490
                    if (now>_monitorNext)
                    {
                        _selects=(int)(_selects*__MONITOR_PERIOD/(now-_monitorStart));
                        _pausing=_selects>__MAX_SELECTS;
                        if (_pausing)
                            _paused++;
                            
                        _selects=0;
                        _jvmBug=0;
                        _monitorStart=now;
                        _monitorNext=now+__MONITOR_PERIOD;
                    }
                    
                    if (now>_log)
                    {
                        if (_paused>0)  
                            Log.info(this+" Busy selector - injecting delay "+_paused+" times");

                        if (_jvmFix2>0)
                            Log.info(this+" JVM BUG(s) - injecting delay"+_jvmFix2+" times");

                        if (_jvmFix1>0)
                            Log.info(this+" JVM BUG(s) - recreating selector "+_jvmFix1+" times, canceled keys "+_jvmFix0+" times");

                        else if(Log.isDebugEnabled() && _jvmFix0>0)
                            Log.info(this+" JVM BUG(s) - canceled keys "+_jvmFix0+" times");
                        _paused=0;
                        _jvmFix2=0;
                        _jvmFix1=0;
                        _jvmFix0=0;
                        _log=now+60000;
                    }
                    
                    // If we see signature of possible JVM bug, increment count.
                    if (selected==0 && wait>10 && (now-before)<(wait/2))
                    {
                        // Increment bug count and try a work around
                        _jvmBug++;
                        if (_jvmBug>(__JVMBUG_THRESHHOLD))
                        {
                            try
                            {
                                if (_jvmBug==__JVMBUG_THRESHHOLD+1)
                                    _jvmFix2++;
                                    
                                Thread.sleep(__BUSY_PAUSE); // pause to avoid busy loop
                            }
                            catch(InterruptedException e)
                            {
                                Log.ignore(e);
                            }
                        }
                        else if (_jvmBug==__JVMBUG_THRESHHOLD)
                        {
                            synchronized (this)
                            {
                                // BLOODY SUN BUG !!!  Try refreshing the entire selector.
                                final Selector new_selector = Selector.open();
                                for (SelectionKey k: selector.keys())
                                {
                                    if (!k.isValid() || k.interestOps()==0)
                                        continue;
                                    
                                    final SelectableChannel channel = k.channel();
                                    final Object attachment = k.attachment();
                                    
                                    if (attachment==null)
                                        addChange(channel);
                                    else
                                        addChange(channel,attachment);
                                }
                                _selector.close();
                                _selector=new_selector;
                                return;
                            }
                        }
                        else if (_jvmBug%32==31) // heuristic attempt to cancel key 31,63,95,... loops
                        {
                            // Cancel keys with 0 interested ops
                            int cancelled=0;
                            for (SelectionKey k: selector.keys())
                            {
                                if (k.isValid()&&k.interestOps()==0)
                                {
                                    k.cancel();
                                    cancelled++;
                                }
                            }
                            if (cancelled>0)
                                _jvmFix0++;
                            
                            return;
                        }
                    }
                    else if (__BUSY_KEY>0 && selected==1 && _selects>__MAX_SELECTS)
                    {
                        // Look for busy key
                        SelectionKey busy = selector.selectedKeys().iterator().next();
                        if (busy==_busyKey)
                        {
                            if (++_busyKeyCount>__BUSY_KEY && !(busy.channel() instanceof ServerSocketChannel))
                            {
                                SelectChannelEndPoint endpoint = (SelectChannelEndPoint)busy.attachment();
                                Log.warn("Busy Key "+busy.channel()+" "+endpoint);
                                busy.cancel();
                                if (endpoint!=null)
                                    endpoint.close();
                            }
                        }
                        else
                            _busyKeyCount=0;
                        _busyKey=busy;
                    }
                }
                else 
                {
                    selector.selectNow();
                    _selects++;
                }

                // have we been destroyed while sleeping
                if (_selector==null || !selector.isOpen())
                    return;

                // Look for things to do
                for (SelectionKey key: selector.selectedKeys())
                {   
                    try
                    {
                        if (!key.isValid())
                        {
                            key.cancel();
                            SelectChannelEndPoint endpoint = (SelectChannelEndPoint)key.attachment();
                            if (endpoint != null)
                                endpoint.doUpdateKey();
                            continue;
                        }

                        Object att = key.attachment();
                        if (att instanceof SelectChannelEndPoint)
                        {
                            ((SelectChannelEndPoint)att).schedule();
                        }
                        else if (key.isAcceptable())
                        {
                            SocketChannel channel = acceptChannel(key);
                            if (channel==null)
                                continue;

                            channel.configureBlocking(false);

                            // TODO make it reluctant to leave 0
                            _nextSet=++_nextSet%_selectSet.length;

                            // Is this for this selectset
                            if (_nextSet==_setID)
                            {
                                // bind connections to this select set.
                                SelectionKey cKey = channel.register(_selectSet[_nextSet].getSelector(), SelectionKey.OP_READ);
                                SelectChannelEndPoint endpoint=newEndPoint(channel,_selectSet[_nextSet],cKey);
                                cKey.attach(endpoint);
                                if (endpoint != null)
                                    endpoint.schedule();
                            }
                            else
                            {
                                // nope - give it to another.
                                _selectSet[_nextSet].addChange(channel);
                                _selectSet[_nextSet].wakeup();
                            }
                        }
                        else if (key.isConnectable())
                        {
                            // Complete a connection of a registered channel
                            SocketChannel channel = (SocketChannel)key.channel();
                            boolean connected=false;
                            try
                            {
                                connected=channel.finishConnect();
                            }
                            catch(Exception e)
                            {
                                connectionFailed(channel,e,att);
                            }
                            finally
                            {
                                if (connected)
                                {
                                    key.interestOps(SelectionKey.OP_READ);
                                    SelectChannelEndPoint endpoint = newEndPoint(channel,this,key);
                                    key.attach(endpoint);
                                    endpoint.schedule();
                                }
                                else
                                {
                                    key.cancel();
                                }
                            }
                        }
                        else
                        {
                            // Wrap readable registered channel in an endpoint
                            SocketChannel channel = (SocketChannel)key.channel();
                            SelectChannelEndPoint endpoint = newEndPoint(channel,this,key);
                            key.attach(endpoint);
                            if (key.isReadable())
                                endpoint.schedule();                           
                        }
                        key = null;
                    }
                    catch (CancelledKeyException e)
                    {
                        Log.ignore(e);
                    }
                    catch (Exception e)
                    {
                        if (isRunning())
                            Log.warn(e);
                        else
                            Log.ignore(e);

                        if (key != null && !(key.channel() instanceof ServerSocketChannel) && key.isValid())
                            key.cancel();
                    }
                }
                
                // Everything always handled
                selector.selectedKeys().clear();
                
                // tick over the timers
                _idleTimeout.tick(now);
                _timeout.tick(now);
            }
            catch (CancelledKeyException e)
            {
                Log.ignore(e);
            }
            finally
            {
                _selecting=null;
            }
        }

        /* ------------------------------------------------------------ */
        public SelectorManager getManager()
        {
            return SelectorManager.this;
        }

        /* ------------------------------------------------------------ */
        public long getNow()
        {
            return _idleTimeout.getNow();
        }
        
        /* ------------------------------------------------------------ */
        public void scheduleIdle(Timeout.Task task)
        {
            if (_idleTimeout.getDuration() <= 0)
                return;
            _idleTimeout.schedule(task);
        }

        /* ------------------------------------------------------------ */
        public void scheduleTimeout(Timeout.Task task, long timeoutMs)
        {
            _timeout.schedule(task, timeoutMs);
        }
        
        /* ------------------------------------------------------------ */
        public void cancelTimeout(Timeout.Task task)
        {
            task.cancel();
        }

        /* ------------------------------------------------------------ */
        public void wakeup()
        {
            Selector selector = _selector;
            if (selector!=null)
                selector.wakeup();
        }

        /* ------------------------------------------------------------ */
        Selector getSelector()
        {
            return _selector;
        }
        
        /* ------------------------------------------------------------ */
        void stop() throws Exception
        {
            boolean selecting=true;
            while(selecting)
            {
                wakeup();
                selecting=_selecting!=null;
            }

            for (SelectionKey key:_selector.keys())
            {
                if (key==null)
                    continue;
                Object att=key.attachment();
                if (att instanceof EndPoint)
                {
                    EndPoint endpoint = (EndPoint)att;
                    try
                    {
                        endpoint.close();
                    }
                    catch(IOException e)
                    {
                        Log.ignore(e);
                    }
                }
            }
            
            synchronized (this)
            {
                selecting=_selecting!=null;
                while(selecting)
                {
                    wakeup();
                    selecting=_selecting!=null;
                }
                
                _idleTimeout.cancelAll();
                _timeout.cancelAll();
                try
                {
                    if (_selector != null)
                        _selector.close();
                }
                catch (IOException e)
                {
                    Log.ignore(e);
                } 
                _selector=null;
            }
        }
        
        public void dump()
        {
            synchronized (System.err)
            {
                Selector selector=_selector;
                Log.info("SelectSet "+_setID+" "+selector.keys().size());
                for (SelectionKey key: selector.keys())
                {
                    if (key.isValid())
                        Log.info(key.channel()+" "+key.interestOps()+" "+key.readyOps()+" "+key.attachment());
                    else
                        Log.info(key.channel()+" - - "+key.attachment());
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    private static class ChangeSelectableChannel
    {
        final SelectableChannel _channel;
        final Object _attachment;
        
        public ChangeSelectableChannel(SelectableChannel channel, Object attachment)
        {
            super();
            _channel = channel;
            _attachment = attachment;
        }
    }

    /* ------------------------------------------------------------ */
    private interface ChangeTask
    {
        public void run();
    }
}
