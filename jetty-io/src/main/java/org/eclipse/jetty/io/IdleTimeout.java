package org.eclipse.jetty.io;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;



/* ------------------------------------------------------------ */
/** An Abstract implementation of an Idle Timeout.
 * 
 * This implementation is optimised that timeout operations are not cancelled on
 * every operation. Rather timeout are allowed to expire and a check is then made 
 * to see when the last operation took place.  If the idle timeout has not expired, 
 * the timeout is rescheduled for the earliest possible time a timeout could occur.
 * 
 */
public abstract class IdleTimeout
{
    private static final Logger LOG = Log.getLogger(IdleTimeout.class);
    private final Scheduler _scheduler;
    private final AtomicReference<Scheduler.Task> _timeout = new AtomicReference<>();
    private volatile long _idleTimeout;
    private volatile long _idleTimestamp=System.currentTimeMillis();
    
    private final Runnable _idleTask = new Runnable()
    {
        @Override
        public void run()
        {
            long idleLeft=checkIdleTimeout();
            if (idleLeft>=0)
                scheduleIdleTimeout(idleLeft > 0 ? idleLeft : getIdleTimeout());
        }
    };

    /* ------------------------------------------------------------ */
    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public IdleTimeout(Scheduler scheduler)
    {
        _scheduler=scheduler;
    }
    
    public long getIdleTimestamp()
    {
        return _idleTimestamp;
    }
    
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    /* ------------------------------------------------------------ */
    /** This method should be called when non-idle activity has taken place.
     */
    public void notIdle()
    {
        _idleTimestamp=System.currentTimeMillis();
    }
    
    protected void scheduleIdleTimeout(long delay)
    {
        Scheduler.Task newTimeout = null;
        if (isOpen() && delay > 0 && _scheduler!=null)
            newTimeout = _scheduler.schedule(_idleTask, delay, TimeUnit.MILLISECONDS);
        Scheduler.Task oldTimeout = _timeout.getAndSet(newTimeout);
        if (oldTimeout != null)
            oldTimeout.cancel();
    }
    
    protected void close()
    {
        Scheduler.Task oldTimeout = _timeout.getAndSet(null);
        if (oldTimeout != null)
            oldTimeout.cancel();
    }
    
    protected long checkIdleTimeout()
    {
        if (isOpen())
        {
            long idleTimestamp = getIdleTimestamp();
            long idleTimeout = getIdleTimeout();
            long idleElapsed = System.currentTimeMillis() - idleTimestamp;
            long idleLeft = idleTimeout - idleElapsed;

            LOG.debug("{} idle timeout check, elapsed: {} ms, remaining: {} ms", this, idleElapsed, idleLeft);

            if (idleTimestamp != 0 && idleTimeout > 0)
            {
                if (idleLeft <= 0)
                {
                    LOG.debug("{} idle timeout expired", this);
                    try
                    {
                        onIdleExpired(new TimeoutException("Idle timeout expired: " + idleElapsed + "/" + idleTimeout + " ms"));
                    }
                    finally
                    {
                        notIdle();
                    }
                }
            }

            return idleLeft>=0?idleLeft:0;
        }
        return -1;
    }
    
    /* ------------------------------------------------------------ */
    /** This abstract method is called when the idle timeout has expired.
     * @param timeout a TimeoutException
     */
    abstract protected void onIdleExpired(TimeoutException timeout);
    
    
    /* ------------------------------------------------------------ */
    /** This abstract method should be called to check if idle timeouts
     * should still be checked.
     * @return True if the entity monitored should still be checked for idle timeouts
     */
    abstract protected boolean isOpen();
}
