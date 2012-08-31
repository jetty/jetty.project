package org.eclipse.jetty.util.thread;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class SimpleScheduler extends AbstractLifeCycle implements Scheduler
{
    /* this class uses the Timer class rather than an ScheduledExecutionService because
     * it uses the same algorithm internally and the signature is cheaper to use as there are no
     * Futures involved (which we do not need).
     * However, Timer is still locking and a concurrent queue would be better.
     */
    Timer _timer;
    final String _name;
    
    public SimpleScheduler()
    {
        this(null);
    }
    
    public SimpleScheduler(String name)
    {
        _name=name;
    }
    
    @Override
    protected void doStart() throws Exception
    {
        _timer=_name==null?new Timer():new Timer(_name);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _timer.cancel();
        super.doStop();
        _timer=null;
    }

    @Override
    public Task schedule(final Runnable task, final long delay, final TimeUnit units)
    {
        Timer timer=_timer;
        if (timer!=null)
        {
           SimpleTask t = new SimpleTask(task);
           _timer.schedule(t,units.toMillis(delay));
           return t;
        }
        throw new IllegalStateException("STOPPED: "+this);
    }
    
    private static class SimpleTask extends TimerTask implements Task
    {
        private final Runnable _task;
        
        SimpleTask(Runnable runnable)
        {
            _task=runnable;
        }
        
        @Override
        public void run()
        {
            _task.run();
        }
    }
    
    
}
