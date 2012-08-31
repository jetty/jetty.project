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
 * It is optimized on the assumption that most timers either get cancelled quickly or last to expiry, thus 
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

    public ConcurrentScheduler(Executor executor)
    {
        this(executor,5000);
    }
    
    public ConcurrentScheduler(Executor executor,int delayQms)
    {
        _executor = executor;
        addBean(_executor,false);
        _delayQ=new Queue(delayQms);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
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
            if (interval<=0 && event._state.compareAndSet(State.NEW,State.DONE))
            {
                _executor.execute(event._task);
            }
            // Should we delay this event
            else if (_delayQ._delay>0 && interval>_delayQ._delay)
            {
                long dequeue_at = now + _delayQ._delay;
                _delayQ.add(event,dequeue_at);
            }
            // else we schedule the event
            else if (event._state.compareAndSet(State.NEW,State.SCHEDULED))
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
                                _timerQ.add(event);
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
                            if (event._state.compareAndSet(State.SCHEDULED,State.DONE))
                            {
                                _executor.execute(event._task);
                            }
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

    
    enum State { NEW, DELAYED, SCHEDULED, CANCELLED, DONE };
    

    private class Event implements Scheduler.Task
    {
        final Runnable _task;
        final long _executeAt;
        final AtomicReference<State> _state=new AtomicReference<>(State.NEW);
        volatile QNode _node;
        
        public Event(Runnable task, long executeAt)
        {
            super();
            _task = task;
            _executeAt = executeAt;
        }

        public boolean isScheduled()
        {
            return _state.get()==State.SCHEDULED;
        }

        @Override
        public boolean cancel()
        {
            while(true)
            {
                switch(_state.get())
                {
                    case NEW:
                        throw new IllegalStateException();
                      
                    case DONE:
                    case CANCELLED:
                        return false;
                    case DELAYED:
                        if (_state.compareAndSet(State.DELAYED,State.CANCELLED))
                        {
                            _node.cancel();
                            return true;
                        }
                        break;
                    case SCHEDULED:
                        if (_state.compareAndSet(State.SCHEDULED,State.CANCELLED))
                        {
                            _timerQ.remove(this);
                            return true;
                        }
                        break;
                }
            }
        }
        
        @Override
        public String toString()
        {
            return String.format("Event@%x{%s,%d,%s}",hashCode(),_state,_executeAt,_task);
        }
    }
    
    
    
    
    private static class Queue
    {
        final int _delay;
        final QNode _head = new QNode(null,0,null,null);
        final QNode _tail = new QNode(null,0,null,null);
        
        Queue(int delay)
        {
            _delay=delay;
            _head._next.set(_tail);
            _tail._prev=_head;
        }
        
        void clear()
        {
            _head._next.set(_tail);
            _tail._prev=_head;
        }
        
        void add(Event event, long dequeue_at)
        {
            if (event._state.compareAndSet(State.NEW,State.DELAYED))
            {
                while (true) 
                {
                    QNode prev = _tail.prev();

                    if (prev!=null)
                    {
                        QNode node = new QNode(event,dequeue_at,prev,_tail);
                        if (prev._next.compareAndSet(_tail,node))
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
    private static class QNode 
    {
        /* These are invarients
         * 
         * (1) next references are the ground truth. 
         * 
         * (2) next references to dead nodes can be improved by
         * swinging them further forward around the dead node.
         * 
         * (2.1) next references are still correct when pointing to dead nodes
         * and next references from dead nodes are left as they were when the node 
         * was deleted.
         * 
         * (2.2) multiple dead nodes may point forward to the same node. 
         * 
         * (3) backward pointers were correct when they were installed.
         * 
         * (3.1) backward pointers are correct when pointing to any node which 
         * next reference points to them, but since more than one next reference 
         * may point to them, the live one is best. 
         * 
         * (4) backward pointers that are out of date due to deletion
         * point to a deleted node, and need to point further back until they point
         * to the live node that points to their source. 
         * 
         * (5) backward pointers from a dead node cannot be "improved" since there may be no live node pointing
         * forward to their origin. (However, it does no harm to try to improve them
         * while racing with a deletion.)
         * 
         */
        
        final Event _event;
        final long _dequeueAt;
        final AtomicReference<QNode> _next=new AtomicReference<>();
        volatile QNode _prev;
        
        QNode(Event event, long dequeue_at, QNode prev, QNode next)
        {
            _event=event;
            _dequeueAt=dequeue_at;
            _prev=prev;
            _next.set(next);
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
                QNode prev_next = prev._next.get();
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
            QNode next = _next.get();
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
                QNode next_next = next._next.get();
                _next.compareAndSet(next, next_next); 
                next = next_next;
            }
        }
        
        public boolean cancel()
        {
            if (_event._state.compareAndSet(State.DELAYED,State.CANCELLED))
            {
                QNode prev = _prev;
                QNode next = _next.get();
                if (prev != null && next != null && next.isDelayed())
                {
                    if (prev._next.compareAndSet(this, next))
                        next._prev=prev;
                }
                return true;
            }
            return false;
        }
        
        public Event dequeue()
        {
            if (_event._state.compareAndSet(State.DELAYED,State.SCHEDULED))
            {
                QNode prev = _prev;
                QNode next = _next.get();
                if (prev != null && next != null && next.isDelayed())
                {
                    if (prev._next.compareAndSet(this, next))
                        next._prev=prev;
                }
                return _event;
            }
            return null;
        }

        public boolean isDelayed()
        {
            return _event!=null && _event._state.get()==State.DELAYED;
        }
        
        public boolean isTail()
        {
            return _event==null && _next.get()==null;
        }
        

        @Override
        public String toString()
        {
            QNode p=_prev;
            QNode n=_next.get();
            return String.format("QNode@%x{%x<-%s->%x}",hashCode(),p==null?0:p.hashCode(),_event,n==null?0:n.hashCode());
        }
    }

}
