//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import java.nio.channels.IllegalSelectorException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/*------------------------------------------------------------ */
/**
 * This is an experimental no-lock scheduler. 
 * It is optimised on the assumption that most timers either get cancelled quickly or last to expiry, thus 
 * events are initially queued in a constant time queue implemented with a no-lock FIFO.   Events
 * that are likely to be cancelled quickly will be removed from the delay list when they are cancelled.
 * The events that survive the delay list are then queued for executiong using a ConcurrentSkipListSet to 
 * keep them ordered, but also to support cheap cancellations.
 *
 * More work is needed to see if this class is correct and really more efficient than the 
 * SimpleScheduler based on Timeout.
 */
public class ConcurrentScheduler extends AggregateLifeCycle implements Runnable, Scheduler
{
    private static final Logger LOG = Log.getLogger(ConcurrentScheduler.class);
    private static final int MAX_SLEEP=1024;
    private final Executor _executor;
    private volatile Thread _runner;
    
    private final ConcurrentSkipListSet<Event> _timerQ=new ConcurrentSkipListSet<>(new Comparator<Event>()
    {
        @Override
        public int compare(Event e1, Event e2)
        {
            return e1==e2?0:(e1._executeAt<e2._executeAt?-1:1);
        }
    });
    private final Queue _delayQ;

    public ConcurrentScheduler()
    {
        this(null,8192);
    }
    
    public ConcurrentScheduler(Executor executor)
    {
        this(executor,8192);
    }
    
    public ConcurrentScheduler(int delayQms)
    {
        this(null,delayQms);
    }
    
    public ConcurrentScheduler(Executor executor,int delayQms)
    {
        _executor = executor;
        if (_executor!=null)
            addBean(_executor,false);
        _delayQ=new Queue(delayQms);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        if (_executor==null)
            new Thread(this).start();
        else
            _executor.execute(this);
    }


    @Override
    protected void doStop() throws Exception
    {
        Thread runner=_runner;
        if (runner!=null)
            runner.interrupt();
        super.doStop();
        
        _timerQ.clear();
        _delayQ.clear();
    }

    @Override
    public Task schedule(Runnable task, long delay, TimeUnit units)
    {
        long ms=units.toMillis(delay);
        long now = System.currentTimeMillis();
        long execute_at=now+ms;
        
        Event event = new Event(task,execute_at);
        schedule(event,now);
        return event;
    }
    
    private void schedule(Event event, long now)
    {
        if (isStarted())
        {
            long interval=event._executeAt-now;
            
            // Should we execute this event?
            if (interval<=0 && event.compareAndSet(State.NEW,State.DONE))
                event.execute();
            
            // Should we delay this event
            else if (_delayQ._delay>0 && interval>_delayQ._delay)
            {
                long dequeue_at = now + _delayQ._delay;
                _delayQ.add(event,dequeue_at);
            }
            // else we schedule the event
            else if (event.compareAndSet(State.NEW,State.SCHEDULED))
            {
                _timerQ.add(event);
                if (interval<=MAX_SLEEP)
                {
                    Thread th=_runner;
                    if (th!=null)
                        th.interrupt();
                }
            }
            else
                throw new IllegalSelectorException();
        }
    }

    @Override
    public void run()
    {
        try
        {
            _runner=Thread.currentThread();
            while(isRunning())
            {
                try
                {
                    // Work out how long to sleep for and execute expired events
                    long now=System.currentTimeMillis();
                    long sleep=MAX_SLEEP;
                    
                    // Process delay Q
                    QNode next=_delayQ._head.next(); 
                    
                    while (next!=null && !next.isTail())
                    {
                        long dequeue_at = next._dequeueAt;
                        if (dequeue_at<=now)
                        {
                            Event event=next.dequeue();
                            if (event!=null)
                            {
                                if (event._executeAt<=now)
                                {
                                    if (event.compareAndSet(State.SCHEDULED,State.DONE))
                                        event.execute();
                                }
                                else
                                {
                                    _timerQ.add(event);
                                }
                            }
                        }
                        else 
                        {
                            long interval=dequeue_at-now;
                            if (interval<sleep)
                                sleep=interval;
                            break;
                        }
                        next=_delayQ._head.next(); 
                    }   
                    
                    // Process schedule Q
                    for (Iterator<Event> i=_timerQ.iterator();i.hasNext();)
                    {
                        Event event=i.next();
                        
                        // Is the event still scheduled?
                        if (!event.isScheduled())
                            i.remove();

                        // is it ready to execute
                        else if (event._executeAt<=now)
                        {
                            i.remove();
                            event.execute();
                            if (event.compareAndSet(State.SCHEDULED,State.DONE))
                                event.execute();
                        }
                        // else how long do we need to wait?
                        else
                        {
                            long interval=event._executeAt-now;
                            if (interval<sleep)
                                sleep=interval;
                            break;
                        }
                    }
                    
                    // Sleep
                    if (sleep>0)
                    {
                        Thread.sleep(sleep);
                    }
                }
                catch(InterruptedException i)
                {
                    LOG.ignore(i);
                }
            }
        }
        finally
        {
            _runner=null;
        }
    }


    @Override
    public String toString()
    {
        return String.format("%s@%x{%d,%s}",this.getClass().getSimpleName(),hashCode(),_delayQ._delay,_executor);
    }
    
    enum State { NEW, DELAYED, SCHEDULED, CANCELLED, DONE };
    

    private class Event extends AtomicReference<State> implements Scheduler.Task
    {
        /* extends AtomicReference as a minor optimisation rather than holding a _state field */
        final Runnable _task;
        final long _executeAt;
        volatile QNode _node;
        
        public Event(Runnable task, long executeAt)
        {
            super(State.NEW);
            _task = task;
            _executeAt = executeAt;
        }

        public boolean isScheduled()
        {
            return get()==State.SCHEDULED;
        }

        @Override
        public boolean cancel()
        {
            while(true)
            {
                switch(get())
                {
                    case NEW:
                        throw new IllegalStateException();
                      
                    case DONE:
                    case CANCELLED:
                        return false;
                    case DELAYED:
                        if (compareAndSet(State.DELAYED,State.CANCELLED))
                        {
                            _node.cancel();
                            return true;
                        }
                        break;
                    case SCHEDULED:
                        if (compareAndSet(State.SCHEDULED,State.CANCELLED))
                        {
                            _timerQ.remove(this);
                            return true;
                        }
                        break;
                }
            }
        }
        
        public void execute()
        {
            if (_executor==null)
                _task.run();
            else
                _executor.execute(_task);
        }
        
        @Override
        public String toString()
        {
            return String.format("Event@%x{%s,%d,%s}",hashCode(),get(),_executeAt,_task);
        }
    }
    
    
    

    /* ------------------------------------------------------------ */
    /**
     *  A no lock FIFO queue used to provide a fixed delay for the handling
     *  of scheduled events.   
     */
    private static class Queue
    {
       /*
        * Roughly based on public domain lock free queue algorithm from: 
        * http://www.java2s.com/Code/Java/Collections-Data-Structure/ConcurrentDoublyLinkedList.htm
        * 
        * Importantly this implementation supports pro active removal of nodes when cancel is called. 
        * It does not use deletion markers as the intention is to allow the node to be rapidly garbage collected.
        */
        
        final int _delay;
        final QNode _head = new QNode(null,0,null,null);
        final QNode _tail = new QNode(null,0,null,null);
        
        Queue(int delay)
        {
            _delay=delay;
            _head.set(_tail);
            _tail._prev=_head;
        }
        
        void clear()
        {
            _head.set(_tail);
            _tail._prev=_head;
        }
        
        void add(Event event, long dequeue_at)
        {
            if (event.compareAndSet(State.NEW,State.DELAYED))
            {
                while (true) 
                {
                    QNode prev = _tail.prev();

                    if (prev!=null)
                    {
                        QNode node = new QNode(event,dequeue_at,prev,_tail);
                        if (prev.compareAndSet(_tail,node))
                        {
                            _tail._prev=node;
                            event._node=node;
                            return;
                        }
                    }
                }
            }
            else
                throw new IllegalStateException();
        }
        
        @Override
        public String toString()
        {
            StringBuilder b = new StringBuilder();
            b.append(String.format("Q@%x{%d,",hashCode(),_delay));
            b.append(_head);
            QNode next=_head.next();
            if (next!=null && !next.isTail())
            {
                b.append("->");
                b.append(next);

                next=next.next();
                if (next!=null && !next.isTail())
                    b.append("...");
            }

            b.append("->");
            b.append(_tail);
            b.append("}");
            return b.toString();
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /** An Event in a constant time queue.
     * Roughly based on public domain lock free queue algorithm from: 
     * http://www.java2s.com/Code/Java/Collections-Data-Structure/ConcurrentDoublyLinkedList.htm
     */
    private static class QNode extends AtomicReference<QNode>
    {   
        /* extends AtomicReference as a minor optimisation rather than holding a _next field */
        final Event _event;
        final long _dequeueAt;
        volatile QNode _prev;
        
        QNode(Event event, long dequeue_at, QNode prev, QNode next)
        {
            super(next);
            _event=event;
            _dequeueAt=dequeue_at;
            _prev=prev;
        }

        /**
         * Returns the previous non-deleted event, patching up pointers as needed.
         */
        QNode prev()
        {
            QNode event = this;
            while(true) 
            {
                QNode prev = event._prev;
                
                // If the event has no previous
                if (prev == null)
                    // event must be head, so scan forward from it
                    // to find ourselves to determine the previous.
                    return event.scanForPrevOf(this);
                
                // If the previous next is this (still linked normally)
                QNode prev_next = prev.get();
                if (prev_next==this)
                    return prev;
                
                if (prev_next==null || prev_next.isDelayed())
                {
                    QNode p = prev.scanForPrevOf(this);
                    if (p!=null)
                        return p;
                }
                
                event = prev;
            }
        }
        

        /**
         * Returns the apparent predecessor of target by searching forward for
         * it starting at this node, patching up pointers while traversing. Used
         * by predecessor().
         * 
         * @return target's previous, or null if not found
         */
        private QNode scanForPrevOf(QNode target)
        {
            QNode scan = this;
            while (true) 
            {
                QNode next = scan.next();
                if (next == target)
                    return scan;
                if (next == null)
                    return null;
                scan = next;
            }
        }
        

        /**
         * Returns the next non-deleted event, swinging next pointer around any
         * encountered deleted events, and also patching up previous''s prev
         * link to point back to this. Returns null if this event is trailer so
         * has no successor.
         * 
         * @return successor, or null if no such
         */
        QNode next()
        {
            QNode next = get();
            while (true) 
            {
                if (next == null)
                    return null;
                if (next.isDelayed() || next.isTail()) 
                {
                    if (next._prev != this && isDelayed())
                        next._prev=this; 
                    return next;
                }
                QNode next_next = next.get();
                compareAndSet(next, next_next); 
                next = next_next;
            }
        }
        
        public boolean cancel()
        {
            if (_event.compareAndSet(State.DELAYED,State.CANCELLED))
            {
                QNode prev = _prev;
                QNode next = get();
                if (prev != null && next != null && next.isDelayed())
                {
                    if (prev.compareAndSet(this, next))
                        next._prev=prev;
                }
                return true;
            }
            return false;
        }
        
        public Event dequeue()
        {
            if (_event.compareAndSet(State.DELAYED,State.SCHEDULED))
            {
                QNode prev = _prev;
                QNode next = get();
                if (prev != null && next != null && next.isDelayed())
                {
                    if (prev.compareAndSet(this, next))
                        next._prev=prev;
                }
                return _event;
            }
            return null;
        }

        public boolean isDelayed()
        {
            return _event!=null && _event.get()==State.DELAYED;
        }
        
        public boolean isTail()
        {
            return _event==null && get()==null;
        }
        

        @Override
        public String toString()
        {
            QNode p=_prev;
            QNode n=get();
            return String.format("QNode@%x{%x<-%s->%x}",hashCode(),p==null?0:p.hashCode(),_event,n==null?0:n.hashCode());
        }
    }

}
