package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/* ------------------------------------------------------------ */
/** Dispatched IOFuture.
 * <p>An implementation of IOFuture that can be extended to implement the
 * {@link #dispatch(Runnable)} method so that callbacks can be dispatched.
 * By default, the callbacks are called by the thread that called {@link #ready()} or 
 * {@link #fail(Throwable)}
 */
public class DispatchedIOFuture implements IOFuture
{
    private final Lock _lock;
    private final Condition _block;
    
    private boolean _complete;
    private boolean _ready;
    private Throwable _cause;
    
    private Callback _callback;
    
    public DispatchedIOFuture()
    {
        // System.err.println(this);new Throwable().printStackTrace();
        _lock = new ReentrantLock();
        _block = _lock.newCondition();
    }
    
    public DispatchedIOFuture(Lock lock)
    {
        // System.err.println(this);new Throwable().printStackTrace();
        _lock = lock;
        _block = _lock.newCondition();
    }
    
    public DispatchedIOFuture(boolean ready,Lock lock)
    {
        _ready=ready;
        _complete=ready;
        _lock = lock;
        _block = _lock.newCondition();
    }
    
    public void fail(final Throwable cause)
    {
        // System.err.println(this);new Throwable().printStackTrace();
        _lock.lock();
        try
        {
            if (_complete)
                throw new IllegalStateException("complete",cause);
            
            _cause=cause;
            _complete=true;
            
            if (_callback!=null)
                dispatchFail();
            _block.signal();
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    public void ready()
    {
        // System.err.println(this);new Throwable().printStackTrace();
        _lock.lock();
        try
        {
            if (_complete)
                throw new IllegalStateException();
            _ready=true;
            _complete=true;

            if (_callback!=null)
                dispatchReady();
            _block.signal();
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    protected void cancelled()
    {
        // System.err.println(this);new Throwable().printStackTrace();
        _lock.lock();
        try
        {
            if (_complete)
                throw new IllegalStateException();
            _ready=false;
            _complete=true;
            _block.signal();
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    @Override
    public boolean isComplete()
    {
        _lock.lock();
        try
        {
            return _complete;
        }
        finally
        {
            _lock.unlock();
        }
    }

    @Override
    public boolean isReady() throws ExecutionException
    {
        _lock.lock();
        try
        {
            if (_complete)
            {
                if (_ready)
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
            while (!_complete)
                _block.await();
            isReady();
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
            if (!_complete)
                _block.await(timeout,units);
            return isReady();
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    @Override
    public void setCallback(Callback callback)
    {
        _lock.lock();
        try
        {
            if (_callback!=null)
                throw new IllegalStateException();
            _callback=callback;
            
            if (_complete)
            {
                if (_ready)
                    dispatchReady();
                else
                    dispatchFail();
            }
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    protected void dispatch(Runnable callback)
    {
        new Thread(callback).run();
    }

    private void dispatchReady()
    {
        final Callback callback=_callback;
        _callback=null;
        dispatch(new Runnable()
        {
            @Override
            public void run()
            {
                callback.onReady();
            }
        });
    }
    
    private void dispatchFail()
    {
        final Callback callback=_callback;
        final Throwable cause=_cause;
        _callback=null;
        dispatch(new Runnable()
        {
            @Override
            public void run()
            {
                callback.onFail(cause);
            }
        });
    }
    


    @Override
    public String toString()
    {
        return String.format("DIOF@%x{%s,%s}",
                hashCode(),
                _complete?(_ready?"R":_cause):"-",
                _callback==null?"-":_callback);
    }

    public static void rethrow(ExecutionException e) throws IOException
    {
        if (e.getCause() instanceof IOException)
            throw (IOException)e.getCause();
        throw new RuntimeException(e);
    }
}
