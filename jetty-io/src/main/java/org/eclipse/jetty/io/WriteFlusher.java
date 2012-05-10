package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;


/* ------------------------------------------------------------ */
/** 
 * A Utility class to help implement {@link AsyncEndPoint#write(Object, Callback, ByteBuffer...)}
 * by calling {@link EndPoint#flush(ByteBuffer...)} until all content is written.
 * The abstract method {@link #scheduleCompleteWrite()} is called when not all content has been 
 * written after a call to flush and should organise for the {@link #completeWrite()}
 * method to be called when a subsequent call to flush should be able to make more progress.
 * 
 */
abstract public class WriteFlusher
{
    private final static ByteBuffer[] NO_BUFFERS= new ByteBuffer[0];
    private final AtomicBoolean _writing = new AtomicBoolean(false);
    private final EndPoint _endp;
    
    private ByteBuffer[] _writeBuffers;
    private Object _writeContext;
    private Callback _writeCallback;
    
    protected WriteFlusher(EndPoint endp)
    {
        _endp=endp;
    }

    /* ------------------------------------------------------------ */
    public void write(Object context, Callback callback, ByteBuffer... buffers)
    {
        if (!_writing.compareAndSet(false,true))
            throw new WritePendingException();
        try
        {
            _endp.flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    _writeBuffers=buffers;
                    _writeContext=context;
                    _writeCallback=callback;
                    scheduleCompleteWrite();
                    _writing.set(true); // Needed as memory barrier
                    return;
                }
            }

            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException();
            callback.completed(context);
        }
        catch (IOException e)
        {
            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException();
            callback.failed(context,e);
        }
    }
    
    /* ------------------------------------------------------------ */
    abstract protected void scheduleCompleteWrite();

    
    /* ------------------------------------------------------------ */
    /* Remove empty buffers from the start of a multi buffer array
     */
    private ByteBuffer[] compact(ByteBuffer[] buffers)
    {
        if (buffers.length<2)
            return buffers;
        int b=0;
        while (b<buffers.length && BufferUtil.isEmpty(buffers[b]))
            b++;
        if (b==0)
            return buffers;
        if (b==buffers.length)
            return NO_BUFFERS;
        
        ByteBuffer[] compact=new ByteBuffer[buffers.length-b];
        
        for (int i=0;i<compact.length;i++)
            compact[i]=buffers[b+i];
        return compact;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Complete a write that has not completed and that called 
     * {@link #scheduleCompleteWrite()} to request a call to this
     * method when a call to {@link EndPoint#flush(ByteBuffer...)} 
     * is likely to be able to progress.
     * @return true if a write was in progress
     */
    public boolean completeWrite()
    {
        if (!_writing.get())
            return false;

        try
        {
            _writeBuffers=compact(_writeBuffers);
            _endp.flush(_writeBuffers);

            // Are we complete?
            for (ByteBuffer b : _writeBuffers)
            {
                if (b.hasRemaining())
                {
                    scheduleCompleteWrite();
                    return true;
                }
            }
            
            // we are complete and ready
            Callback callback=_writeCallback;
            Object context=_writeContext;
            _writeBuffers=null;
            _writeCallback=null;
            _writeContext=null;
            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException();
            callback.completed(context);
        }
        catch (IOException e)
        {
            Callback callback=_writeCallback;
            Object context=_writeContext;
            _writeBuffers=null;
            _writeCallback=null;
            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException();
            callback.failed(context,e);
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Fail the write in progress and cause any calls to get to throw
     * the cause wrapped as an execution exception.
     * @return true if a write was in progress
     */
    public boolean failed(Throwable cause)
    {
        if (!_writing.compareAndSet(true,false))
            return false;
        Callback callback=_writeCallback;
        Object context=_writeContext;
        _writeBuffers=null;
        _writeCallback=null;
        callback.failed(context,cause);
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Fail the write with a {@link ClosedChannelException}. This is similar
     * to a call to {@link #failed(Throwable)}, except that the exception is 
     * not instantiated unless a write was in progress.
     * @return true if a write was in progress
     */
    public boolean close()
    {
        if (!_writing.compareAndSet(true,false))
            return false;
        Callback callback=_writeCallback;
        Object context=_writeContext;
        _writeBuffers=null;
        _writeCallback=null;
        callback.failed(context,new ClosedChannelException());
        return true;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isWriting()
    {
        return _writing.get();
    }
    
}