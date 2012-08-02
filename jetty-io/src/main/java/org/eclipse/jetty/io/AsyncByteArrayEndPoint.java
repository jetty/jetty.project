package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncByteArrayEndPoint extends ByteArrayEndPoint implements AsyncEndPoint, Runnable
{
    private static final Logger LOG = Log.getLogger(AsyncByteArrayEndPoint.class);

    private final ReadInterest _readInterest = new ReadInterest()
    {
        @Override
        protected boolean needsFill() throws IOException
        {
            if (_closed)
                throw new ClosedChannelException();
            return _in == null || BufferUtil.hasContent(_in);
        }
    };
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlushed()
        {
            // Don't need to do anything here as takeOutput does the signalling.
        }
    };
    private final AtomicReference<Future<?>> _timeout = new AtomicReference<>();
    private final ScheduledExecutorService _scheduler;
    private volatile AsyncConnection _connection;

    public AsyncByteArrayEndPoint(ScheduledExecutorService scheduler, long idleTimeout)
    {
        _scheduler = scheduler;
        setIdleTimeout(idleTimeout);
    }

    public AsyncByteArrayEndPoint(ScheduledExecutorService timer, long idleTimeout, byte[] input, int outputSize)
    {
        super(input, outputSize);
        _scheduler = timer;
        setIdleTimeout(idleTimeout);
    }

    public AsyncByteArrayEndPoint(ScheduledExecutorService timer, long idleTimeout, String input, int outputSize)
    {
        super(input, outputSize);
        _scheduler = timer;
        setIdleTimeout(idleTimeout);
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        scheduleIdleTimeout(idleTimeout);
    }

    private void scheduleIdleTimeout(long delay)
    {
        Future<?> newTimeout = isOpen() && delay > 0 ? _scheduler.schedule(this, delay, TimeUnit.MILLISECONDS) : null;
        Future<?> oldTimeout = _timeout.getAndSet(newTimeout);
        if (oldTimeout != null)
            oldTimeout.cancel(false);
    }

    @Override
    public void run()
    {
        if (isOpen())
        {
            long idleTimestamp = getIdleTimestamp();
            long idleTimeout = getIdleTimeout();
            long idleElapsed = System.currentTimeMillis() - idleTimestamp;
            long idleLeft = idleTimeout - idleElapsed;

            LOG.debug("{} idle timeout check, elapsed: {} ms, remaining: {} ms", this, idleElapsed, idleLeft);

            if (isOutputShutdown() || _readInterest.isInterested() || _writeFlusher.isWritePending())
            {
                if (idleTimestamp != 0 && idleTimeout > 0)
                {
                    if (idleLeft <= 0)
                    {
                        LOG.debug("{} idle timeout expired", this);

                        TimeoutException timeout = new TimeoutException("Idle timeout expired: " + idleElapsed + "/" + idleTimeout + " ms");
                        _readInterest.failed(timeout);
                        _writeFlusher.failed(timeout);

                        if (isOutputShutdown())
                            close();
                        notIdle();
                    }
                }
            }
            scheduleIdleTimeout(idleLeft > 0 ? idleLeft : idleTimeout);
        }
    }

    @Override
    public void setInput(ByteBuffer in)
    {
        super.setInput(in);
        if (in == null || BufferUtil.hasContent(in))
            _readInterest.readable();
    }

    @Override
    public ByteBuffer takeOutput()
    {
        ByteBuffer b = super.takeOutput();
        _writeFlusher.completeWrite();
        return b;
    }

    @Override
    public void setOutput(ByteBuffer out)
    {
        super.setOutput(out);
        _writeFlusher.completeWrite();
    }

    @Override
    public void reset()
    {
        _readInterest.close();
        _writeFlusher.close();
        super.reset();
    }

    @Override
    public <C> void fillInterested(C context, Callback<C> callback) throws IllegalStateException
    {
        _readInterest.register(context, callback);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        _writeFlusher.write(context, callback, buffers);
    }

    @Override
    public AsyncConnection getAsyncConnection()
    {
        return _connection;
    }

    @Override
    public void setAsyncConnection(AsyncConnection connection)
    {
        _connection = connection;
    }

    @Override
    public void onOpen()
    {
    }

    @Override
    public void onClose()
    {
    }
}
