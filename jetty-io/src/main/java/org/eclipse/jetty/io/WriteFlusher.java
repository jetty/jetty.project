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
 * The abstract method {@link #registerFlushInterest()} is called when not all content has been
 * written after a call to flush and should organise for the {@link #completeWrite()}
 * method to be called when a subsequent call to flush should be able to make more progress.
 *
 */
abstract public class WriteFlusher
{
    private final static ByteBuffer[] NO_BUFFERS= new ByteBuffer[0];
    private final AtomicBoolean _writing = new AtomicBoolean(false);
    private final EndPoint _endp;

    private ByteBuffer[] _buffers;
    private Object _context;
    private Callback _callback;

    protected WriteFlusher(EndPoint endp)
    {
        _endp=endp;
    }

    /* ------------------------------------------------------------ */
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers)
    {
        if (callback==null)
            throw new IllegalArgumentException();
        if (!_writing.compareAndSet(false,true))
            throw new WritePendingException();
        try
        {
            _buffers=buffers;
            _context=context;
            _callback=callback;

            _endp.flush(buffers);

            // Are we complete?
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    if(registerFlushInterest())
                        completeWrite();
                    else
                        _writing.set(true); // Needed as memory barrier
                    return;
                }
            }

            _buffers=null;
            _context=null;
            _callback=null;

            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException();
            callback.completed(context);
        }
        catch (IOException e)
        {
            _buffers=null;
            _context=null;
            _callback=null;

            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException(e);
            callback.failed(context,e);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Abstract call to be implemented by specific WriteFlushers. Will return true if a
     * flush is immediately possible, otherwise it will schedule a call to {@link #completeWrite()} or
     * {@link #failed(Throwable)} when appropriate.
     * @return true if a flush can proceed.
     */
    abstract protected boolean registerFlushInterest();


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
        System.arraycopy(buffers,b,compact,0,compact.length);
        return compact;
    }

    /* ------------------------------------------------------------ */
    /**
     * Complete a write that has not completed and that called
     * {@link #registerFlushInterest()} to request a call to this
     * method when a call to {@link EndPoint#flush(ByteBuffer...)}
     * is likely to be able to progress.
     * @return true if a write was in progress
     */
    public boolean completeWrite()
    {
        if (!isWriting())
            return false;

        try
        {
            retry: while(true)
            {
                _buffers=compact(_buffers);
                _endp.flush(_buffers);

                // Are we complete?
                for (ByteBuffer b : _buffers)
                {
                    if (b.hasRemaining())
                    {
                        if (registerFlushInterest())
                            continue retry;
                        return true;
                    }
                }
                break;
            }
            // we are complete and ready
            Callback callback=_callback;
            Object context=_context;
            _buffers=null;
            _callback=null;
            _context=null;
            if (!_writing.compareAndSet(true,false))
                throw new ConcurrentModificationException();
            callback.completed(context);
        }
        catch (IOException e)
        {
            Callback callback=_callback;
            Object context=_context;
            _buffers=null;
            _callback=null;
            _context=null;
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
        Callback callback=_callback;
        Object context=_context;
        _buffers=null;
        _callback=null;
        _context=null;
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
        Callback callback=_callback;
        Object context=_context;
        _buffers=null;
        _callback=null;
        _context=null;
        callback.failed(context,new ClosedChannelException());
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean isWriting()
    {
        return _writing.get();
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("WriteFlusher@%x{%b,%s,%s}",hashCode(),isWriting(),_callback,_context);
    }
}
