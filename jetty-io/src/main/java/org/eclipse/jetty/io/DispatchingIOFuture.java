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
public class DispatchingIOFuture implements IOFuture
{
    private final Object _lock;
    private boolean _done;
    private boolean _complete;
    private Throwable _cause;
    private Callback<?> _callback;
    private Object _context;

    public DispatchingIOFuture()
    {
        this(null);
    }

    public DispatchingIOFuture(Object lock)
    {
        this(false, lock);
    }

    public DispatchingIOFuture(boolean ready,Object lock)
    {
        _complete=ready;
        _done=ready;
        _lock = lock==null?this:lock;
    }

    public void fail(final Throwable cause)
    {
        synchronized(_lock)
        {
            if (_done)
                throw new IllegalStateException("complete",cause);

            _cause=cause;
            _done=true;

            if (_callback!=null)
                dispatchFailed();
            _lock.notifyAll();
        }
    }

    public void complete()
    {
        synchronized(_lock)
        {
            if (_done)
                throw new IllegalStateException();
            _complete=true;
            _done=true;

            if (_callback!=null)
                dispatchCompleted();
            _lock.notifyAll();
        }
    }

    protected void cancelled()
    {
        synchronized(_lock)
        {
            if (_done)
                throw new IllegalStateException();
            _complete=false;
            _done=true;
            _lock.notifyAll();
        }
    }

    @Override
    public boolean isDone()
    {
        synchronized(_lock)
        {
            return _done;
        }
    }

    @Override
    public boolean isComplete() throws ExecutionException
    {
        synchronized(_lock)
        {
            if (_done)
            {
                if (_complete)
                    return true;
                throw new ExecutionException(_cause);
            }

            return false;
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
        synchronized(_lock)
        {
            while (!_done)
                _lock.wait();
            isComplete();
        }
    }


    @Override
    public boolean block(long timeout, TimeUnit units) throws InterruptedException, ExecutionException
    {
        synchronized(_lock)
        {
            if (!_done)
                _lock.wait(units.toMillis(timeout));
            return isComplete();
        }
    }

    @Override
    public <C> void setCallback(Callback<C> callback, C context)
    {
        synchronized(_lock)
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
