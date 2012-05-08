package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.Callback;


/* ------------------------------------------------------------ */
/** Dispatched IOFuture.
 * <p>An implementation of IOFuture that can be extended to implement the
 * {@link #dispatch(Runnable)} method so that callbacks can be dispatched.
 * By default, the callbacks are called by the thread that called {@link #complete()} or
 * {@link #fail(Throwable)}
 */
public class DispatchedIOFuture implements IOFuture
{
    private final Lock _lock;
    private final Condition _block;
    private boolean _done;
    private boolean _complete;
    private Throwable _cause;
    private Callback<?> _callback;
    private Object _context;

    public DispatchedIOFuture()
    {
        this(new ReentrantLock());
    }

    public DispatchedIOFuture(Lock lock)
    {
        this(false, lock);
    }

    public DispatchedIOFuture(boolean ready,Lock lock)
    {
        _complete=ready;
        _done=ready;
        _lock = lock;
        _block = _lock.newCondition();
    }

    public void fail(final Throwable cause)
    {
        _lock.lock();
        try
        {
            if (_done)
                throw new IllegalStateException("complete",cause);

            _cause=cause;
            _done=true;

            if (_callback!=null)
                dispatchFailed();
            _block.signal();
        }
        finally
        {
            _lock.unlock();
        }
    }

    public void complete()
    {
        _lock.lock();
        try
        {
            if (_done)
                throw new IllegalStateException();
            _complete=true;
            _done=true;

            if (_callback!=null)
                dispatchCompleted();
            _block.signal();
        }
        finally
        {
            _lock.unlock();
        }
    }

    protected void cancelled()
    {
        _lock.lock();
        try
        {
            if (_done)
                throw new IllegalStateException();
            _complete=false;
            _done=true;
            _block.signal();
        }
        finally
        {
            _lock.unlock();
        }
    }

    @Override
    public boolean isDone()
    {
        _lock.lock();
        try
        {
            return _done;
        }
        finally
        {
            _lock.unlock();
        }
    }

    @Override
    public boolean isComplete() throws ExecutionException
    {
        _lock.lock();
        try
        {
            if (_done)
            {
                if (_complete)
                    return true;
                throw new ExecutionException(_cause);
            }

            return false;
        }
        finally
        {
            _lock.unlock();
        }
    }

    @Override
    public void cancel() throws UnsupportedOperationException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void block() throws InterruptedException, ExecutionException
    {
        _lock.lock();
        try
        {
            while (!_done)
                _block.await();
            isComplete();
        }
        finally
        {
            _lock.unlock();
        }
    }


    @Override
    public boolean block(long timeout, TimeUnit units) throws InterruptedException, ExecutionException
    {
        _lock.lock();
        try
        {
            if (!_done)
                _block.await(timeout,units);
            return isComplete();
        }
        finally
        {
            _lock.unlock();
        }
    }

    @Override
    public <C> void setCallback(Callback<C> callback, C context)
    {
        _lock.lock();
        try
        {
            if (_callback!=null)
                throw new IllegalStateException();
            _callback=callback;
            _context=context;

            if (_done)
            {
                if (_complete)
                    dispatchCompleted();
                else
                    dispatchFailed();
            }
        }
        finally
        {
            _lock.unlock();
        }
    }

    protected void dispatch(Runnable callback)
    {
        new Thread(callback).start();
    }

    private void dispatchCompleted()
    {
        final Callback callback=_callback;
        final Object context=_context;
        dispatch(new Runnable()
        {
            @Override
            public void run()
            {
                callback.completed(context);
            }
        });
    }

    private void dispatchFailed()
    {
        final Callback callback=_callback;
        final Throwable cause=_cause;
        final Object context=_context;
        dispatch(new Runnable()
        {
            @Override
            public void run()
            {
                callback.failed(context, cause);
            }
        });
    }

    @Override
    public String toString()
    {
        return String.format("DIOF@%x{%s,%s}",
                hashCode(),
                _done?(_complete?"R":_cause):"-",
                _callback==null?"-":_callback);
    }

    public static void rethrow(ExecutionException e) throws IOException
    {
        if (e.getCause() instanceof IOException)
            throw (IOException)e.getCause();
        throw new RuntimeException(e);
    }
}
