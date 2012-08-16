package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class AbstractEndPoint implements EndPoint
{
    private static final Logger LOG = Log.getLogger(AbstractEndPoint.class);
    private final long _created=System.currentTimeMillis();
    private final InetSocketAddress _local;
    private final InetSocketAddress _remote;

    private final ScheduledExecutorService _scheduler;
    private final AtomicReference<Future<?>> _timeout = new AtomicReference<>();
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
    
    
    private volatile long _idleTimeout;
    private volatile long _idleTimestamp=System.currentTimeMillis();
    private volatile Connection _connection;

    private final FillInterest _fillInterest = new FillInterest()
    {
        @Override
        protected boolean needsFill() throws IOException
        {
            return AbstractEndPoint.this.needsFill();
        }
    };
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlushed()
        {
            AbstractEndPoint.this.onIncompleteFlush();
        }
    };

    protected AbstractEndPoint(ScheduledExecutorService scheduler,InetSocketAddress local,InetSocketAddress remote)
    {
        _local=local;
        _remote=remote;
        _scheduler=scheduler;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return _created;
    }


    @Override
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return _local;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return _remote;
    }

    public long getIdleTimestamp()
    {
        return _idleTimestamp;
    }

    protected void notIdle()
    {
        _idleTimestamp=System.currentTimeMillis();
    }

    @Override
    public Connection getConnection()
    {
        return _connection;
    }

    @Override
    public void setConnection(Connection connection)
    {
        _connection = connection;
    }

    @Override
    public void onOpen()
    {
        LOG.debug("onOpen {}",this);
    }

    @Override
    public void onClose()
    {
        LOG.debug("onClose {}",this);
        _writeFlusher.onClose();
        _fillInterest.onClose();
    }

    @Override
    public <C> void fillInterested(C context, Callback<C> callback) throws IllegalStateException
    {
        _fillInterest.register(context, callback);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        _writeFlusher.write(context, callback, buffers);
    }
    
    protected abstract void onIncompleteFlush();

    protected abstract boolean needsFill() throws IOException;
    
    protected FillInterest getFillInterest()
    {
        return _fillInterest;
    }
    
    protected WriteFlusher getWriteFlusher()
    {
        return _writeFlusher;
    }

    protected void scheduleIdleTimeout(long delay)
    {
        Future<?> newTimeout = null;
        if (isOpen() && delay > 0 && _scheduler!=null)
            newTimeout = _scheduler.schedule(_idleTask, delay, TimeUnit.MILLISECONDS);
        Future<?> oldTimeout = _timeout.getAndSet(newTimeout);
        if (oldTimeout != null)
            oldTimeout.cancel(false);
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

            if (isOutputShutdown() || _fillInterest.isInterested() || _writeFlusher.isInProgress())
            {
                if (idleTimestamp != 0 && idleTimeout > 0)
                {
                    if (idleLeft <= 0)
                    {
                        LOG.debug("{} idle timeout expired", this);

                        TimeoutException timeout = new TimeoutException("Idle timeout expired: " + idleElapsed + "/" + idleTimeout + " ms");
                        _fillInterest.onFail(timeout);
                        _writeFlusher.onFail(timeout);

                        if (isOutputShutdown())
                            close();
                        notIdle();
                    }
                }
            }
            
            return idleLeft>=0?idleLeft:0;
        }
        return -1;
    }
    
    @Override
    public String toString()
    {
        
        return String.format("%s@%x{%s<r-l>%s,o=%b,is=%b,os=%b,fi=%s,wf=%s}{%s}",
                getClass().getSimpleName(),
                hashCode(),
                getRemoteAddress(),
                getLocalAddress(),
                isOpen(),
                isInputShutdown(),
                isOutputShutdown(),
                _fillInterest,
                _writeFlusher,
                getConnection());
    }
}
