package org.eclipse.jetty.io;

import java.util.concurrent.locks.Lock;

/* ------------------------------------------------------------ */
/** A Dispatched IOFuture that retains the Runnable.
 * This IOFuture captures a dispatched task as a runnable that can be executed later. 
 * This is often used when the {@link #ready()} or {@link #fail(Throwable)} method is 
 * called holding locks that should not be held during the execution of the runnable.
 */
final class RunnableIOFuture extends DispatchedIOFuture
{
    private volatile Runnable _task;
    
    RunnableIOFuture(boolean ready, Lock lock)
    {
        super(ready,lock);
    }

    @Override
    protected void dispatch(Runnable callback)
    {
        if (_task!=null)
            throw new IllegalStateException();
        _task=callback;
    }
    
    public Runnable takeTask()
    {
        Runnable t=_task;
        _task=null;
        return t;
    }
    
    public void run()
    {
        Runnable task=takeTask();
        if (task!=null)
            task.run();
    }
    
    public boolean isDispatched()
    {
        return _task!=null;
    }
    
    @Override 
    public void recycle()
    {
        if (_task!=null)
            throw new IllegalStateException("unrun task");
        super.recycle();
    }
}