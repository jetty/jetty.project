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
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.util.thread.Timeout.Task;


/* ------------------------------------------------------------ */
/**
 * The Selector Manager manages and number of SelectSets to allow
 * NIO scheduling to scale to large numbers of connections.
 * <p>
 */
public abstract class SelectorManager extends AbstractLifeCycle implements Dumpable
{
    public static final Logger LOG=Log.getLogger("org.eclipse.jetty.io.nio");

    private static final int __MONITOR_PERIOD=Integer.getInteger("org.eclipse.jetty.io.nio.MONITOR_PERIOD",1000).intValue();
    private static final int __MAX_SELECTS=Integer.getInteger("org.eclipse.jetty.io.nio.MAX_SELECTS",100000).intValue();
    private static final int __BUSY_PAUSE=Integer.getInteger("org.eclipse.jetty.io.nio.BUSY_PAUSE",50).intValue();
    private static final int __IDLE_TICK=Integer.getInteger("org.eclipse.jetty.io.nio.IDLE_TICK",400).intValue();

    private int _maxIdleTime;
    private int _lowResourcesMaxIdleTime;
    private long _lowResourcesConnections;
    private SelectSet[] _selectSet;
    private int _selectSets=1;
    private volatile int _set=0;
    private boolean _deferringInterestedOps0=true;
    private int _selectorPriorityDelta=0;

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
        if (s<0)
            s=-s;
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
    /** Register a channel
     * @param channel
     */
    public void register(SocketChannel channel)
    {
        // The ++ increment here is not atomic, but it does not matter.
        // so long as the value changes sometimes, then connections will
        // be distributed over the available sets.

        int s=_set++;
        if (s<0)
            s=-s;
        s=s%_selectSets;
        SelectSet[] sets=_selectSet;
        if (sets!=null)
        {
            SelectSet set=sets[s];
            set.addChange(channel);
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
        if (s<0)
            s=-s;
        s=s%_selectSets;
        SelectSet set=_selectSet[s];
        set.addChange(acceptChannel);
        set.wakeup();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return delta The value to add to the selector thread priority.
     */
    public int getSelectorPriorityDelta()
    {
        return _selectorPriorityDelta;
    }

    /* ------------------------------------------------------------ */
    /** Set the selector thread priorty delta.
     * @param delta The value to add to the selector thread priority.
     */
    public void setSelectorPriorityDelta(int delta)
    {
        _selectorPriorityDelta=delta;
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

        // start a thread to Select
        for (int i=0;i<getSelectSets();i++)
        {
            final int id=i;
            boolean selecting=dispatch(new Runnable()
            {
                public void run()
                {
                    String name=Thread.currentThread().getName();
                    int priority=Thread.currentThread().getPriority();
                    try
                    {
                        SelectSet[] sets=_selectSet;
                        if (sets==null)
                            return;
                        SelectSet set=sets[id];

                        Thread.currentThread().setName(name+" Selector"+id);
                        if (getSelectorPriorityDelta()!=0)
                            Thread.currentThread().setPriority(Thread.currentThread().getPriority()+getSelectorPriorityDelta());
                        LOG.debug("Starting {} on {}",Thread.currentThread(),this);
                        while (isRunning())
                        {
                            try
                            {
                                set.doSelect();
                            }
                            catch(IOException e)
                            {
                                LOG.ignore(e);
                            }
                            catch(Exception e)
                            {
                                LOG.warn(e);
                            }
                        }
                    }
                    finally
                    {
                        LOG.debug("Stopped {} on {}",Thread.currentThread(),this);
                        Thread.currentThread().setName(name);
                        if (getSelectorPriorityDelta()!=0)
                            Thread.currentThread().setPriority(priority);
                    }
                }

            });

            if (!selecting)
                throw new IllegalStateException("!Selecting");
        }
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
    public abstract AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment);

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
        LOG.warn(ex+","+channel+","+attachment);
        LOG.debug(ex);
    }

    /* ------------------------------------------------------------ */
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    /* ------------------------------------------------------------ */
    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out,indent,TypeUtil.asList(_selectSet));
    }


    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    public class SelectSet implements Dumpable
    {
        private final int _setID;
        private final Timeout _timeout;

        private final ConcurrentLinkedQueue<Object> _changes = new ConcurrentLinkedQueue<Object>();

        private volatile Selector _selector;

        private volatile Thread _selecting;
        private int _busySelects;
        private long _monitorNext;
        private boolean _pausing;
        private boolean _paused;
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
            _monitorNext=System.currentTimeMillis()+__MONITOR_PERIOD;
        }

        /* ------------------------------------------------------------ */
        public void addChange(Object change)
        {
            _changes.add(change);
        }

        /* ------------------------------------------------------------ */
        public void addChange(SelectableChannel channel, Object att)
        {
            if (att==null)
                addChange(channel);
            else if (att instanceof EndPoint)
                addChange(att);
            else
                addChange(new ChannelAndAttachment(channel,att));
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
                // Stopped concurrently ?
                if (selector == null)
                    return;

                // Make any key changes required
                Object change;
                int changes=_changes.size();
                while (changes-->0 && (change=_changes.poll())!=null)
                {
                    Channel ch=null;
                    SelectionKey key=null;

                    try
                    {
                        if (change instanceof EndPoint)
                        {
                            // Update the operations for a key.
                            SelectChannelEndPoint endpoint = (SelectChannelEndPoint)change;
                            ch=endpoint.getChannel();
                            endpoint.doUpdateKey();
                        }
                        else if (change instanceof ChannelAndAttachment)
                        {
                            // finish accepting/connecting this connection
                            final ChannelAndAttachment asc = (ChannelAndAttachment)change;
                            final SelectableChannel channel=asc._channel;
                            ch=channel;
                            final Object att = asc._attachment;

                            if ((channel instanceof SocketChannel) && ((SocketChannel)channel).isConnected())
                            {
                                key = channel.register(selector,SelectionKey.OP_READ,att);
                                SelectChannelEndPoint endpoint = createEndPoint((SocketChannel)channel,key);
                                key.attach(endpoint);
                                endpoint.schedule();
                            }
                            else if (channel.isOpen())
                            {
                                key = channel.register(selector,SelectionKey.OP_CONNECT,att);
                            }
                        }
                        else if (change instanceof SocketChannel)
                        {
                            // Newly registered channel
                            final SocketChannel channel=(SocketChannel)change;
                            ch=channel;
                            key = channel.register(selector,SelectionKey.OP_READ,null);
                            SelectChannelEndPoint endpoint = createEndPoint(channel,key);
                            key.attach(endpoint);
                            endpoint.schedule();
                        }
                        else if (change instanceof ChangeTask)
                        {
                            ((Runnable)change).run();
                        }
                        else if (change instanceof Runnable)
                        {
                            dispatch((Runnable)change);
                        }
                        else
                            throw new IllegalArgumentException(change.toString());
                    }
                    catch (CancelledKeyException e)
                    {
                        LOG.ignore(e);
                    }
                    catch (Throwable e)
                    {
                        if (isRunning())
                            LOG.warn(e);
                        else
                            LOG.debug(e);

                        try
                        {
                            if (ch!=null)
                                ch.close();
                        }
                        catch(IOException e2)
                        {
                            LOG.debug(e2);
                        }
                    }
                }


                // Do and instant select to see if any connections can be handled.
                int selected=selector.selectNow();

                long now=System.currentTimeMillis();

                // if no immediate things to do
                if (selected==0 && selector.selectedKeys().isEmpty())
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
                            LOG.ignore(e);
                        }
                        now=System.currentTimeMillis();
                    }

                    // workout how long to wait in select
                    _timeout.setNow(now);
                    long to_next_timeout=_timeout.getTimeToNext();

                    long wait = _changes.size()==0?__IDLE_TICK:0L;
                    if (wait > 0 && to_next_timeout >= 0 && wait > to_next_timeout)
                        wait = to_next_timeout;

                    // If we should wait with a select
                    if (wait>0)
                    {
                        long before=now;
                        selector.select(wait);
                        now = System.currentTimeMillis();
                        _timeout.setNow(now);

                        // If we are monitoring for busy selector
                        // and this select did not wait more than 1ms
                        if (__MONITOR_PERIOD>0 && now-before <=1)
                        {
                            // count this as a busy select and if there have been too many this monitor cycle
                            if (++_busySelects>__MAX_SELECTS)
                            {
                                // Start injecting pauses
                                _pausing=true;

                                // if this is the first pause
                                if (!_paused)
                                {
                                    // Log and dump some status
                                    _paused=true;
                                    LOG.warn("Selector {} is too busy, pausing!",this);
                                }
                            }
                        }
                    }
                }

                // have we been destroyed while sleeping
                if (_selector==null || !selector.isOpen())
                    return;

                // Look for things to do
                for (SelectionKey key: selector.selectedKeys())
                {
                    SocketChannel channel=null;

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
                            if (key.isReadable()||key.isWritable())
                                ((SelectChannelEndPoint)att).schedule();
                        }
                        else if (key.isConnectable())
                        {
                            // Complete a connection of a registered channel
                            channel = (SocketChannel)key.channel();
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
                                    channel.close();
                                }
                            }
                        }
                        else
                        {
                            // Wrap readable registered channel in an endpoint
                            channel = (SocketChannel)key.channel();
                            SelectChannelEndPoint endpoint = createEndPoint(channel,key);
                            key.attach(endpoint);
                            if (key.isReadable())
                                endpoint.schedule();
                        }
                        key = null;
                    }
                    catch (CancelledKeyException e)
                    {
                        LOG.ignore(e);
                    }
                    catch (Exception e)
                    {
                        if (isRunning())
                            LOG.warn(e);
                        else
                            LOG.ignore(e);

                        try
                        {
                            if (channel!=null)
                                channel.close();
                        }
                        catch(IOException e2)
                        {
                            LOG.debug(e2);
                        }

                        if (key != null && !(key.channel() instanceof ServerSocketChannel) && key.isValid())
                            key.cancel();
                    }
                }

                // Everything always handled
                selector.selectedKeys().clear();

                now=System.currentTimeMillis();
                _timeout.setNow(now);
                Task task = _timeout.expired();
                while (task!=null)
                {
                    if (task instanceof Runnable)
                        dispatch((Runnable)task);
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
                        public String toString() {return "Idle-"+super.toString();}
                    });

                }

                // Reset busy select monitor counts
                if (__MONITOR_PERIOD>0 && now>_monitorNext)
                {
                    _busySelects=0;
                    _pausing=false;
                    _monitorNext=now+__MONITOR_PERIOD;

                }
            }
            catch (ClosedSelectorException e)
            {
                if (isRunning())
                    LOG.warn(e);
                else
                    LOG.ignore(e);
            }
            catch (CancelledKeyException e)
            {
                LOG.ignore(e);
            }
            finally
            {
                _selecting=null;
            }
        }


        /* ------------------------------------------------------------ */
        private void renewSelector()
        {
            try
            {
                synchronized (this)
                {
                    Selector selector=_selector;
                    if (selector==null)
                        return;
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
                }
            }
            catch(IOException e)
            {
                throw new RuntimeException("recreating selector",e);
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
            if (!(task instanceof Runnable))
                throw new IllegalArgumentException("!Runnable");
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
            try
            {
                Selector selector = _selector;
                if (selector!=null)
                    selector.wakeup();
            }
            catch(Exception e)
            {
                addChange(new ChangeTask()
                {
                    public void run()
                    {
                        renewSelector();
                    }
                });

                renewSelector();
            }
        }

        /* ------------------------------------------------------------ */
        private SelectChannelEndPoint createEndPoint(SocketChannel channel, SelectionKey sKey) throws IOException
        {
            SelectChannelEndPoint endp = newEndPoint(channel,this,sKey);
            LOG.debug("created {}",endp);
            endPointOpened(endp);
            _endPoints.put(endp,this);
            return endp;
        }

        /* ------------------------------------------------------------ */
        public void destroyEndPoint(SelectChannelEndPoint endp)
        {
            LOG.debug("destroyEndPoint {}",endp);
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
            // Spin for a while waiting for selector to complete
            // to avoid unneccessary closed channel exceptions
            try
            {
                for (int i=0;i<100 && _selecting!=null;i++)
                {
                    wakeup();
                    Thread.sleep(10);
                }
            }
            catch(Exception e)
            {
                LOG.ignore(e);
            }

            // close endpoints and selector
            synchronized (this)
            {
                Selector selector=_selector;
                for (SelectionKey key:selector.keys())
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
                            LOG.ignore(e);
                        }
                    }
                }


                _timeout.cancelAll();
                try
                {
                    selector=_selector;
                    if (selector != null)
                        selector.close();
                }
                catch (IOException e)
                {
                    LOG.ignore(e);
                }
                _selector=null;
            }
        }

        /* ------------------------------------------------------------ */
        public String dump()
        {
            return AggregateLifeCycle.dump(this);
        }

        /* ------------------------------------------------------------ */
        public void dump(Appendable out, String indent) throws IOException
        {
            out.append(String.valueOf(this)).append(" id=").append(String.valueOf(_setID)).append("\n");

            Thread selecting = _selecting;

            Object where = "not selecting";
            StackTraceElement[] trace =selecting==null?null:selecting.getStackTrace();
            if (trace!=null)
            {
                for (StackTraceElement t:trace)
                    if (t.getClassName().startsWith("org.eclipse.jetty."))
                    {
                        where=t;
                        break;
                    }
            }

            Selector selector=_selector;
            if (selector!=null)
            {
                final ArrayList<Object> dump = new ArrayList<Object>(selector.keys().size()*2);
                dump.add(where);

                final CountDownLatch latch = new CountDownLatch(1);

                addChange(new ChangeTask()
                {
                    public void run()
                    {
                        dumpKeyState(dump);
                        latch.countDown();
                    }
                });

                try
                {
                    latch.await(5,TimeUnit.SECONDS);
                }
                catch(InterruptedException e)
                {
                    LOG.ignore(e);
                }

                AggregateLifeCycle.dump(out,indent,dump);
            }
        }

        /* ------------------------------------------------------------ */
        public void dumpKeyState(List<Object> dumpto)
        {
            Selector selector=_selector;
            Set<SelectionKey> keys = selector.keys();
            dumpto.add(selector + " keys=" + keys.size());
            for (SelectionKey key: keys)
            {
                if (key.isValid())
                    dumpto.add(key.attachment()+" iOps="+key.interestOps()+" rOps="+key.readyOps());
                else
                    dumpto.add(key.attachment()+" iOps=-1 rOps=-1");
            }
        }

        /* ------------------------------------------------------------ */
        public String toString()
        {
            Selector selector=_selector;
            return String.format("%s keys=%d selected=%d",
                    super.toString(),
                    selector != null && selector.isOpen() ? selector.keys().size() : -1,
                    selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1);
        }
    }

    /* ------------------------------------------------------------ */
    private static class ChannelAndAttachment
    {
        final SelectableChannel _channel;
        final Object _attachment;

        public ChannelAndAttachment(SelectableChannel channel, Object attachment)
        {
            super();
            _channel = channel;
            _attachment = attachment;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isDeferringInterestedOps0()
    {
        return _deferringInterestedOps0;
    }

    /* ------------------------------------------------------------ */
    public void setDeferringInterestedOps0(boolean deferringInterestedOps0)
    {
        _deferringInterestedOps0 = deferringInterestedOps0;
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private interface ChangeTask extends Runnable
    {}

}
