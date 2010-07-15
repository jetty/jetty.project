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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.util.thread.Timeout.Task;


/* ------------------------------------------------------------ */
/**
 * The Selector Manager manages and number of SelectSets to allow
 * NIO scheduling to scale to large numbers of connections.
 * <p>
 * This class works around a number of know JVM bugs. For details
 * see http://wiki.eclipse.org/Jetty/Feature/JVM_NIO_Bug
 */
public abstract class SelectorManager extends AbstractLifeCycle
{
    // TODO Tune these by approx system speed.
    private static final int __JVMBUG_THRESHHOLD=Integer.getInteger("org.mortbay.io.nio.JVMBUG_THRESHHOLD",512).intValue();
    private static final int __MONITOR_PERIOD=Integer.getInteger("org.mortbay.io.nio.MONITOR_PERIOD",1000).intValue();
    private static final int __MAX_SELECTS=Integer.getInteger("org.mortbay.io.nio.MAX_SELECTS",25000).intValue();
    private static final int __BUSY_PAUSE=Integer.getInteger("org.mortbay.io.nio.BUSY_PAUSE",50).intValue();
    private static final int __BUSY_KEY=Integer.getInteger("org.mortbay.io.nio.BUSY_KEY",-1).intValue();
    private static final int __IDLE_TICK=Integer.getInteger("org.mortbay.io.nio.IDLE_TICK",400).intValue();
    
    private int _maxIdleTime;
    private int _lowResourcesMaxIdleTime;
    private long _lowResourcesConnections;
    private SelectSet[] _selectSet;
    private int _selectSets=1;
    private volatile int _set;
    
    /* ------------------------------------------------------------ */
    /**
     * @param maxIdleTime The maximum period in milli seconds that a connection may be idle before it is closed.
     * @see #setLowResourcesMaxIdleTime(long)
     */
    public void setMaxIdleTime(long maxIdleTime)
    {
        _maxIdleTime=(int)maxIdleTime;
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
     * @return the max idle time
     */
    public long getMaxIdleTime()
    {
        return _maxIdleTime;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the number of select sets in use
     */
    public int getSelectSets()
    {
        return _selectSets;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param i 
     * @return The select set
     */
    public SelectSet getSelectSet(int i)
    {
        return _selectSet[i];
    }
    /* ------------------------------------------------------------ */
    /** Register a channel
     * @param channel
     * @param att Attached Object
     */
    public void register(SocketChannel channel, Object att)
    {
        // The ++ increment here is not atomic, but it does not matter.
        // so long as the value changes sometimes, then connections will
        // be distributed over the available sets.
        
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
    /** Register a {@link ServerSocketChannel}
     * @param acceptChannel
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
     * @see #setLowResourcesMaxIdleTime(long)
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
     * @see #setMaxIdleTime(long)
     */
    public void setLowResourcesMaxIdleTime(long lowResourcesMaxIdleTime)
    {
        _lowResourcesMaxIdleTime=(int)lowResourcesMaxIdleTime;
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
     * @param key the selection key
     * @return the SocketChannel created on accept
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
     * Create a new end point
     * @param channel
     * @param selectSet
     * @param sKey the selection key
     * @return the new endpoint {@link SelectChannelEndPoint}
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
        private final Timeout _timeout;
        
        private final ConcurrentLinkedQueue<Object> _changes = new ConcurrentLinkedQueue<Object>();
        
        private Selector _selector;
        
        private int _nextSet;
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
        private volatile long _idleTick;
        private ConcurrentMap<SelectChannelEndPoint,Object> _endPoints = new ConcurrentHashMap<SelectChannelEndPoint, Object>();
        
        /* ------------------------------------------------------------ */
        SelectSet(int acceptorID) throws Exception
        {
            _setID=acceptorID;

            _idleTick = System.currentTimeMillis();
            _timeout = new Timeout(this);
            _timeout.setDuration(0L);

            // create a selector;
            _selector = Selector.open();
            _monitorStart=System.currentTimeMillis();
            _monitorNext=_monitorStart+__MONITOR_PERIOD;
            _log=_monitorStart+60000;
        }
        
        /* ------------------------------------------------------------ */
        public void addChange(Object point)
        {
            _changes.add(point);
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
                final Selector selector=_selector;

                // Make any key changes required
                Object change;
                int changes=_changes.size();
                while (changes-->0 && (change=_changes.poll())!=null)
                {
                    try
                    {
                        if (change instanceof EndPoint)
                        {
                            // Update the operations for a key.
                            SelectChannelEndPoint endpoint = (SelectChannelEndPoint)change;
                            endpoint.doUpdateKey();
                        }
                        else if (change instanceof Runnable)
                        {
                            dispatch((Runnable)change);
                        }
                        else if (change instanceof ChangeSelectableChannel)
                        {
                            // finish accepting/connecting this connection
                            final ChangeSelectableChannel asc = (ChangeSelectableChannel)change;
                            final SelectableChannel channel=asc._channel;
                            final Object att = asc._attachment;

                            if ((channel instanceof SocketChannel) && ((SocketChannel)channel).isConnected())
                            {
                                SelectionKey key = channel.register(selector,SelectionKey.OP_READ,att);
                                SelectChannelEndPoint endpoint = createEndPoint((SocketChannel)channel,key);
                                key.attach(endpoint);
                                endpoint.schedule();
                            }
                            else if (channel.isOpen())
                            {
                                channel.register(selector,SelectionKey.OP_CONNECT,att);
                            }
                        }
                        else if (change instanceof SocketChannel)
                        {
                            final SocketChannel channel=(SocketChannel)change;

                            if (channel.isConnected())
                            {
                                SelectionKey key = channel.register(selector,SelectionKey.OP_READ,null);
                                SelectChannelEndPoint endpoint = createEndPoint(channel,key);
                                key.attach(endpoint);
                                endpoint.schedule();
                            }
                            else if (channel.isOpen())
                            {
                                channel.register(selector,SelectionKey.OP_CONNECT,null);
                            }
                        }
                        else if (change instanceof ServerSocketChannel)
                        {
                            ServerSocketChannel channel = (ServerSocketChannel)change;
                            channel.register(getSelector(),SelectionKey.OP_ACCEPT);
                        }
                        else if (change instanceof ChangeTask)
                        {
                            ((ChangeTask)change).run();
                        }
                        else
                            throw new IllegalArgumentException(change.toString());
                    }
                    catch (Exception e)
                    {
                        if (isRunning())
                            Log.warn(e);
                        else
                            Log.debug(e);
                    }
                }

                long retry_next;
                long now=System.currentTimeMillis();
                _timeout.setNow(now);

                retry_next=_timeout.getTimeToNext();

                // workout how low to wait in select
                long wait = _changes.size()==0?__IDLE_TICK:0L;  
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
                            Log.debug(this+" Busy selector - injecting delay "+_paused+" times");

                        if (_jvmFix2>0)
                            Log.debug(this+" JVM BUG(s) - injecting delay"+_jvmFix2+" times");

                        if (_jvmFix1>0)
                            Log.debug(this+" JVM BUG(s) - recreating selector "+_jvmFix1+" times, cancelled keys "+_jvmFix0+" times");

                        else if(Log.isDebugEnabled() && _jvmFix0>0)
                            Log.debug(this+" JVM BUG(s) - cancelled keys "+_jvmFix0+" times");
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
                                Selector old_selector=_selector;
                                _selector=new_selector;
                                try
                                {
                                    old_selector.close();
                                }
                                catch(Exception e)
                                {
                                    Log.ignore(e);
                                }
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
                                final SelectChannelEndPoint endpoint = (SelectChannelEndPoint)busy.attachment();
                                Log.warn("Busy Key "+busy.channel()+" "+endpoint);
                                busy.cancel();
                                if (endpoint!=null)
                                {
                                    dispatch(new Runnable()
                                    {
                                        public void run()
                                        {
                                            try
                                            {
                                                endpoint.close();
                                            }
                                            catch (IOException e)
                                            {
                                                Log.ignore(e);
                                            }
                                        }
                                    });
                                }
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
                                
                                SelectChannelEndPoint endpoint=_selectSet[_nextSet].createEndPoint(channel,cKey);
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
                                    SelectChannelEndPoint endpoint = createEndPoint(channel,key);
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
                            SelectChannelEndPoint endpoint = createEndPoint(channel,key);
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
                
                _timeout.setNow(now);
                Task task = _timeout.expired();
                while (task!=null)
                {
                    if (task instanceof Runnable)
                        dispatch((Runnable)task);
                    else
                        task.expired();
                        
                    task = _timeout.expired();
                }

                // Idle tick
                if (now-_idleTick>__IDLE_TICK)
                {
                    _idleTick=now;
                    
                    final long idle_now=((_lowResourcesConnections>0 && selector.keys().size()>_lowResourcesConnections))
                        ?(now+_maxIdleTime-_lowResourcesMaxIdleTime)
                        :now;
                        
                    dispatch(new Runnable()
                    {
                        public void run()
                        {
                            for (SelectChannelEndPoint endp:_endPoints.keySet())
                            {
                                endp.checkIdleTimestamp(idle_now);
                            }
                        }
                    });
                }
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
            return _timeout.getNow();
        }

        /* ------------------------------------------------------------ */
        /**
         * @param task The task to timeout. If it implements Runnable, then 
         * expired will be called from a dispatched thread.
         * 
         * @param timeoutMs
         */
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
        private SelectChannelEndPoint createEndPoint(SocketChannel channel, SelectionKey sKey) throws IOException
        {
            SelectChannelEndPoint endp = newEndPoint(channel,this,sKey);
            endPointOpened(endp); 
            _endPoints.put(endp,this);
            return endp;
        }
        
        /* ------------------------------------------------------------ */
        public void destroyEndPoint(SelectChannelEndPoint endp)
        {
            _endPoints.remove(endp);
            endPointClosed(endp);
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
