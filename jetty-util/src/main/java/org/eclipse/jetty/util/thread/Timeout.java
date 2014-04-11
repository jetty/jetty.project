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

package org.eclipse.jetty.util.thread;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** Timeout queue.
 * This class implements a timeout queue for timers that are at least as likely to be cancelled as they are to expire.
 * Unlike the util timeout class, the duration of the timeouts is shared by all scheduled tasks and if the duration 
 * is changed, this affects all scheduled tasks.
 * <p>
 * The nested class Task should be extended by users of this class to obtain call back notification of 
 * expires. 
 */
public class Timeout
{
    private static final Logger LOG = Log.getLogger(Timeout.class);
    private Object _lock;
    private long _duration;
    private volatile long _now=System.currentTimeMillis();
    private Task _head=new Task();

    /* ------------------------------------------------------------ */
    public Timeout()
    {
        _lock=new Object();
        _head._timeout=this;
    }

    /* ------------------------------------------------------------ */
    public Timeout(Object lock)
    {
        _lock=lock;
        _head._timeout=this;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the duration.
     */
    public long getDuration()
    {
        return _duration;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param duration The duration to set.
     */
    public void setDuration(long duration)
    {
        _duration = duration;
    }

    /* ------------------------------------------------------------ */
    public long setNow()
    {
        return _now=System.currentTimeMillis();
    }
    
    /* ------------------------------------------------------------ */
    public long getNow()
    {
        return _now;
    }

    /* ------------------------------------------------------------ */
    public void setNow(long now)
    {
        _now=now;
    }

    /* ------------------------------------------------------------ */
    /** Get an expired tasks.
     * This is called instead of {@link #tick()} to obtain the next
     * expired Task, but without calling it's {@link Task#expire()} or
     * {@link Task#expired()} methods.
     * 
     * @return the next expired task or null.
     */
    public Task expired()
    {
        synchronized (_lock)
        {
            long _expiry = _now-_duration;

            if (_head._next!=_head)
            {
                Task task = _head._next;
                if (task._timestamp>_expiry)
                    return null;

                task.unlink();
                task._expired=true;
                return task;
            }
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    public void tick()
    {
        final long expiry = _now-_duration;

        Task task=null;
        while (true)
        {
            try
            {
                synchronized (_lock)
                {
                    task= _head._next;
                    if (task==_head || task._timestamp>expiry)
                        break;
                    task.unlink();
                    task._expired=true;
                    task.expire();
                }
                
                task.expired();
            }
            catch(Throwable th)
            {
                LOG.warn(Log.EXCEPTION,th);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void tick(long now)
    {
        _now=now;
        tick();
    }

    /* ------------------------------------------------------------ */
    public void schedule(Task task)
    {
        schedule(task,0L);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param task
     * @param delay A delay in addition to the default duration of the timeout
     */
    public void schedule(Task task,long delay)
    {
        synchronized (_lock)
        {
            if (task._timestamp!=0)
            {
                task.unlink();
                task._timestamp=0;
            }
            task._timeout=this;
            task._expired=false;
            task._delay=delay;
            task._timestamp = _now+delay;

            Task last=_head._prev;
            while (last!=_head)
            {
                if (last._timestamp <= task._timestamp)
                    break;
                last=last._prev;
            }
            last.link(task);
        }
    }


    /* ------------------------------------------------------------ */
    public void cancelAll()
    {
        synchronized (_lock)
        {
            _head._next=_head._prev=_head;
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isEmpty()
    {
        synchronized (_lock)
        {
            return _head._next==_head;
        }
    }

    /* ------------------------------------------------------------ */
    public long getTimeToNext()
    {
        synchronized (_lock)
        {
            if (_head._next==_head)
                return -1;
            long to_next = _duration+_head._next._timestamp-_now;
            return to_next<0?0:to_next;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        StringBuffer buf = new StringBuffer();
        buf.append(super.toString());
        
        Task task = _head._next;
        while (task!=_head)
        {
            buf.append("-->");
            buf.append(task);
            task=task._next;
        }
        
        return buf.toString();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Task.
     * The base class for scheduled timeouts.  This class should be
     * extended to implement the expire() method, which is called if the
     * timeout expires.
     * 
     * 
     *
     */
    public static class Task
    {
        Task _next;
        Task _prev;
        Timeout _timeout;
        long _delay;
        long _timestamp=0;
        boolean _expired=false;

        /* ------------------------------------------------------------ */
        protected Task()
        {
            _next=_prev=this;
        }

        /* ------------------------------------------------------------ */
        public long getTimestamp()
        {
            return _timestamp;
        }

        /* ------------------------------------------------------------ */
        public long getAge()
        {
            final Timeout t = _timeout;
            if (t!=null)
            {
                final long now=t._now;
                if (now!=0 && _timestamp!=0)
                    return now-_timestamp;
            }
            return 0;
        }

        /* ------------------------------------------------------------ */
        private void unlink()
        {
            _next._prev=_prev;
            _prev._next=_next;
            _next=_prev=this;
            _expired=false;
        }

        /* ------------------------------------------------------------ */
        private void link(Task task)
        {
            Task next_next = _next;
            _next._prev=task;
            _next=task;
            _next._next=next_next;
            _next._prev=this;   
        }
        
        /* ------------------------------------------------------------ */
        /** Schedule the task on the given timeout.
         * The task exiry will be called after the timeout duration.
         * @param timer
         */
        public void schedule(Timeout timer)
        {
            timer.schedule(this);
        }
        
        /* ------------------------------------------------------------ */
        /** Schedule the task on the given timeout.
         * The task exiry will be called after the timeout duration.
         * @param timer
         */
        public void schedule(Timeout timer, long delay)
        {
            timer.schedule(this,delay);
        }
        
        /* ------------------------------------------------------------ */
        /** Reschedule the task on the current timeout.
         * The task timeout is rescheduled as if it had been cancelled and
         * scheduled on the current timeout.
         */
        public void reschedule()
        {
            Timeout timeout = _timeout;
            if (timeout!=null)
                timeout.schedule(this,_delay);
        }
        
        /* ------------------------------------------------------------ */
        /** Cancel the task.
         * Remove the task from the timeout.
         */
        public void cancel()
        {
            Timeout timeout = _timeout;
            if (timeout!=null)
            {
                synchronized (timeout._lock)
                {
                    unlink();
                    _timestamp=0;
                }
            }
        }
        
        /* ------------------------------------------------------------ */
        public boolean isExpired() { return _expired; }

        /* ------------------------------------------------------------ */
	public boolean isScheduled() { return _next!=this; }
        
        /* ------------------------------------------------------------ */
        /** Expire task.
         * This method is called when the timeout expires. It is called
         * in the scope of the synchronize block (on this) that sets 
         * the {@link #isExpired()} state to true.
         * @see #expired() For an unsynchronized callback.
         */
        protected void expire(){}

        /* ------------------------------------------------------------ */
        /** Expire task.
         * This method is called when the timeout expires. It is called 
         * outside of any synchronization scope and may be delayed. 
         * 
         */
        public void expired(){}

    }

}
