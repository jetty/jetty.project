package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritePendingException;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;

public class WriteFlusher
{
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
                    _writing.set(true);
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
    protected void scheduleCompleteWrite()
    {
        // _interestOps = _interestOps | SelectionKey.OP_WRITE;
        // updateKey();
    }

    /* ------------------------------------------------------------ */
    public void completeWrite()
    {
        if (!_writing.get())
            return;

        try
        {
            _endp.flush(_writeBuffers);

            // Are we complete?
            for (ByteBuffer b : _writeBuffers)
            {
                if (b.hasRemaining())
                {
                    scheduleCompleteWrite();
                    return;
                }
            }
            
            // we are complete and ready
            Callback callback=_writeCallback;
            Object context=_writeContext;
            _writeBuffers=null;
            _writeCallback=null;
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
    }

    /* ------------------------------------------------------------ */
    public void failWrite(Throwable th)
    {
        if (!_writing.compareAndSet(true,false))
            return;
        Callback callback=_writeCallback;
        Object context=_writeContext;
        _writeBuffers=null;
        _writeCallback=null;
        callback.failed(context,th);
    }

    /* ------------------------------------------------------------ */
    public void close()
    {
        if (!_writing.compareAndSet(true,false))
            return;
        Callback callback=_writeCallback;
        Object context=_writeContext;
        _writeBuffers=null;
        _writeCallback=null;
        callback.failed(context,new ClosedChannelException());
    }
    
    /* ------------------------------------------------------------ */
    public boolean isWriting()
    {
        return _writing.get();
    }
    
}