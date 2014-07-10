//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link HttpOutput} implements {@link ServletOutputStream}
 * as required by the Servlet specification.</p>
 * <p>{@link HttpOutput} buffers content written by the application until a
 * further write will overflow the buffer, at which point it triggers a commit
 * of the response.</p>
 * <p>{@link HttpOutput} can be closed and reopened, to allow requests included
 * via {@link RequestDispatcher#include(ServletRequest, ServletResponse)} to
 * close the stream, to be reopened after the inclusion ends.</p>
 */
public class HttpOutput extends ServletOutputStream implements Runnable
{
    private static Logger LOG = Log.getLogger(HttpOutput.class);
    private final HttpChannel<?> _channel;
    private final SharedBlockingCallback _writeblock=new SharedBlockingCallback();
    private long _written;
    private ByteBuffer _aggregate;
    private int _bufferSize;
    private int _commitSize;
    private WriteListener _writeListener;
    private volatile Throwable _onError;

    /*
    ACTION             OPEN       ASYNC      READY      PENDING       UNREADY       CLOSED
    -----------------------------------------------------------------------------------------------------
    setWriteListener() READY->owp ise        ise        ise           ise           ise
    write()            OPEN       ise        PENDING    wpe           wpe           eof
    flush()            OPEN       ise        PENDING    wpe           wpe           eof
    close()            CLOSED     CLOSED     CLOSED     CLOSED        wpe           CLOSED
    isReady()          OPEN:true  READY:true READY:true UNREADY:false UNREADY:false CLOSED:true
    write completed    -          -          -          ASYNC         READY->owp    -
    
    */
    enum OutputState { OPEN, ASYNC, READY, PENDING, UNREADY, ERROR, CLOSED }
    private final AtomicReference<OutputState> _state=new AtomicReference<>(OutputState.OPEN);

    public HttpOutput(HttpChannel<?> channel)
    {
        _channel = channel;
        _bufferSize = _channel.getHttpConfiguration().getOutputBufferSize();
        _commitSize=_bufferSize/4;
    }
    
    public HttpChannel<?> getHttpChannel()
    {
        return _channel;
    }
    
    public boolean isWritten()
    {
        return _written > 0;
    }

    public long getWritten()
    {
        return _written;
    }

    public void reset()
    {
        _written = 0;
        reopen();
    }

    public void reopen()
    {
        _state.set(OutputState.OPEN);
    }

    public boolean isAllContentWritten()
    {
        return _channel.getResponse().isAllContentWritten(_written);
    }

    protected Blocker acquireWriteBlockingCallback() throws IOException
    {
        return _writeblock.acquire();
    }
    
    protected void write(ByteBuffer content, boolean complete) throws IOException
    {
        try (Blocker blocker=_writeblock.acquire())
        {        
            write(content,complete,blocker);
            blocker.block();
        }
    }
    
    protected void write(ByteBuffer content, boolean complete, Callback callback)
    {
        _channel.write(content,complete,callback);
    }
    
    @Override
    public void close()
    {
        loop: while(true)
        {
            OutputState state=_state.get();
            switch (state)
            {
                case CLOSED:
                    break loop;
                    
                case UNREADY:
                    if (_state.compareAndSet(state,OutputState.ERROR))
                        _writeListener.onError(_onError==null?new EofException("Async close"):_onError);
                    continue;
                    
                default:
                    if (_state.compareAndSet(state,OutputState.CLOSED))
                    {
                        try
                        {
                            write(BufferUtil.hasContent(_aggregate)?_aggregate:BufferUtil.EMPTY_BUFFER,!_channel.getResponse().isIncluding());
                        }
                        catch(IOException e)
                        {
                            LOG.debug(e);
                            _channel.failed();
                        }
                        releaseBuffer();
                        return;
                    }
            }
        }
    }

    /* Called to indicated that the output is already closed (write with last==true performed) and the state needs to be updated to match */
    void closed()
    {
        loop: while(true)
        {
            OutputState state=_state.get();
            switch (state)
            {
                case CLOSED:
                    break loop;
                    
                case UNREADY:
                    if (_state.compareAndSet(state,OutputState.ERROR))
                        _writeListener.onError(_onError==null?new EofException("Async closed"):_onError);
                    continue;
                    
                default:
                    if (_state.compareAndSet(state,OutputState.CLOSED))
                    {
                        try
                        {
                            _channel.getResponse().closeOutput();
                        }
                        catch(IOException e)
                        {
                            LOG.debug(e);
                            _channel.failed();
                        }
                        releaseBuffer();
                        return;
                    }
            }
        }
    }

    private void releaseBuffer()
    {
        if (_aggregate != null)
        {
            _channel.getConnector().getByteBufferPool().release(_aggregate);
            _aggregate = null;
        }
    }

    public boolean isClosed()
    {
        return _state.get()==OutputState.CLOSED;
    }

    @Override
    public void flush() throws IOException
    {
        while(true)
        {
            switch(_state.get())
            {
                case OPEN:
                    write(BufferUtil.hasContent(_aggregate)?_aggregate:BufferUtil.EMPTY_BUFFER, false);
                    return;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;
                    new AsyncFlush().iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);
                    
                case CLOSED:
                    return;
            }
            break;
        }
    }


    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        _written+=len;
        boolean complete=_channel.getResponse().isAllContentWritten(_written);

        // Async or Blocking ?
        while(true)
        {
            switch(_state.get())
            {
                case OPEN:
                    // process blocking below
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;

                    // Should we aggregate?
                    if (!complete && len<=_commitSize)
                    {
                        if (_aggregate == null)
                            _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), false);

                        // YES - fill the aggregate with content from the buffer
                        int filled = BufferUtil.fill(_aggregate, b, off, len);

                        // return if we are not complete, not full and filled all the content
                        if (filled==len && !BufferUtil.isFull(_aggregate))
                        {
                            if (!_state.compareAndSet(OutputState.PENDING, OutputState.ASYNC))
                                throw new IllegalStateException();
                            return;
                        }

                        // adjust offset/length
                        off+=filled;
                        len-=filled;
                    }

                    // Do the asynchronous writing from the callback
                    new AsyncWrite(b,off,len,complete).iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);
                    
                case CLOSED:
                    throw new EofException("Closed");
            }
            break;
        }


        // handle blocking write

        // Should we aggregate?
        int capacity = getBufferSize();
        if (!complete && len<=_commitSize)
        {
            if (_aggregate == null)
                _aggregate = _channel.getByteBufferPool().acquire(capacity, false);

            // YES - fill the aggregate with content from the buffer
            int filled = BufferUtil.fill(_aggregate, b, off, len);

            // return if we are not complete, not full and filled all the content
            if (filled==len && !BufferUtil.isFull(_aggregate))
                return;

            // adjust offset/length
            off+=filled;
            len-=filled;
        }

        // flush any content from the aggregate
        if (BufferUtil.hasContent(_aggregate))
        {
            write(_aggregate, complete && len==0);

            // should we fill aggregate again from the buffer?
            if (len>0 && !complete && len<=_commitSize)
            {
                BufferUtil.append(_aggregate, b, off, len);
                return;
            }
        }

        // write any remaining content in the buffer directly
        if (len>0)
        {
            ByteBuffer wrap = ByteBuffer.wrap(b, off, len);
            ByteBuffer view = wrap.duplicate();

            // write a buffer capacity at a time to avoid JVM pooling large direct buffers
            // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6210541
            while (len>getBufferSize())
            {
                int p=view.position();
                int l=p+getBufferSize();
                view.limit(p+getBufferSize());
                write(view,false);
                len-=getBufferSize();
                view.limit(l+Math.min(len,getBufferSize()));
                view.position(l);
            }
            write(view,complete);
        }
        else if (complete)
            write(BufferUtil.EMPTY_BUFFER,complete);

        if (complete)
            closed();

    }

    public void write(ByteBuffer buffer) throws IOException
    {
        _written+=buffer.remaining();
        boolean complete=_channel.getResponse().isAllContentWritten(_written);

        // Async or Blocking ?
        while(true)
        {
            switch(_state.get())
            {
                case OPEN:
                    // process blocking below
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;

                    // Do the asynchronous writing from the callback
                    new AsyncWrite(buffer,complete).iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);
                    
                case CLOSED:
                    throw new EofException("Closed");
            }
            break;
        }


        // handle blocking write
        int len=BufferUtil.length(buffer);

        // flush any content from the aggregate
        if (BufferUtil.hasContent(_aggregate))
            write(_aggregate, complete && len==0);

        // write any remaining content in the buffer directly
        if (len>0)
            write(buffer, complete);
        else if (complete)
            write(BufferUtil.EMPTY_BUFFER,complete);

        if (complete)
            closed();
    }

    @Override
    public void write(int b) throws IOException
    {
        _written+=1;
        boolean complete=_channel.getResponse().isAllContentWritten(_written);

        // Async or Blocking ?
        while(true)
        {
            switch(_state.get())
            {
                case OPEN:
                    if (_aggregate == null)
                        _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), false);
                    BufferUtil.append(_aggregate, (byte)b);

                    // Check if all written or full
                    if (complete || BufferUtil.isFull(_aggregate))
                    {
                        try(Blocker blocker=_writeblock.acquire())
                        {
                            write(_aggregate, complete, blocker);
                            blocker.block();
                        }
                        if (complete)
                            closed();
                    }
                    break;

                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(OutputState.READY, OutputState.PENDING))
                        continue;

                    if (_aggregate == null)
                        _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), false);
                    BufferUtil.append(_aggregate, (byte)b);

                    // Check if all written or full
                    if (!complete && !BufferUtil.isFull(_aggregate))
                    {
                        if (!_state.compareAndSet(OutputState.PENDING, OutputState.ASYNC))
                            throw new IllegalStateException();
                        return;
                    }

                    // Do the asynchronous writing from the callback
                    new AsyncFlush().iterate();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

                case ERROR:
                    throw new EofException(_onError);
                    
                case CLOSED:
                    throw new EofException("Closed");
            }
            break;
        }
    }

    @Override
    public void print(String s) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");

        write(s.getBytes(_channel.getResponse().getCharacterEncoding()));
    }

    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param content The content to send.
     * @throws IOException
     */
    public void sendContent(ByteBuffer content) throws IOException
    {
        try(Blocker blocker=_writeblock.acquire())
        {
            write(content,true,blocker);
            blocker.block();
        }
    }

    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param in The content to send
     * @throws IOException
     */
    public void sendContent(InputStream in) throws IOException
    {
        try(Blocker blocker=_writeblock.acquire())
        {
            new InputStreamWritingCB(in,blocker).iterate();
            blocker.block();
        }
    }

    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param in The content to send
     * @throws IOException
     */
    public void sendContent(ReadableByteChannel in) throws IOException
    {
        try(Blocker blocker=_writeblock.acquire())
        {
            new ReadableByteChannelWritingCB(in,blocker).iterate();
            blocker.block();
        }
    }


    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param content The content to send
     * @throws IOException
     */
    public void sendContent(HttpContent content) throws IOException
    {
        try(Blocker blocker=_writeblock.acquire())
        {
            sendContent(content,blocker);
            blocker.block();
        }
    }

    /* ------------------------------------------------------------ */
    /** Asynchronous send of content.
     * @param content The content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ByteBuffer content, final Callback callback)
    {
        write(content,true,new Callback()
        {
            @Override
            public void succeeded()
            {
                closed();
                callback.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                callback.failed(x);
            }
        });
    }

    /* ------------------------------------------------------------ */
    /** Asynchronous send of content.
     * @param in The content to send as a stream.  The stream will be closed
     * after reading all content.
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(InputStream in, Callback callback)
    {
        new InputStreamWritingCB(in,callback).iterate();
    }

    /* ------------------------------------------------------------ */
    /** Asynchronous send of content.
     * @param in The content to send as a channel.  The channel will be closed
     * after reading all content.
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ReadableByteChannel in, Callback callback)
    {
        new ReadableByteChannelWritingCB(in,callback).iterate();
    }

    /* ------------------------------------------------------------ */
    /** Asynchronous send of content.
     * @param httpContent The content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(HttpContent httpContent, Callback callback) throws IOException
    {
        if (BufferUtil.hasContent(_aggregate))
            throw new IOException("written");
        if (_channel.isCommitted())
            throw new IOException("committed");

        while (true)
        {
            switch(_state.get())
            {
                case OPEN:
                    if (!_state.compareAndSet(OutputState.OPEN, OutputState.PENDING))
                        continue;
                    break;
                case ERROR:
                    throw new EofException(_onError);
                case CLOSED:
                    throw new EofException("Closed");
                default:
                    throw new IllegalStateException();
            }
            break;
        }
        ByteBuffer buffer= _channel.useDirectBuffers()?httpContent.getDirectBuffer():null;
        if (buffer == null)
            buffer = httpContent.getIndirectBuffer();

        if (buffer!=null)
        {
            sendContent(buffer,callback);
            return;
        }

        ReadableByteChannel rbc=httpContent.getReadableByteChannel();
        if (rbc!=null)
        {
            // Close of the rbc is done by the async sendContent
            sendContent(rbc,callback);
            return;
        }

        InputStream in = httpContent.getInputStream();
        if ( in!=null )
        {
            sendContent(in,callback);
            return;
        }

        callback.failed(new IllegalArgumentException("unknown content for "+httpContent));
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }

    public void setBufferSize(int size)
    {
        _bufferSize = size;
        _commitSize = size;
    }

    public void resetBuffer()
    {
        if (BufferUtil.hasContent(_aggregate))
            BufferUtil.clear(_aggregate);
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        if (!_channel.getState().isAsync())
            throw new IllegalStateException("!ASYNC");

        if (_state.compareAndSet(OutputState.OPEN, OutputState.READY))
        {
            _writeListener = writeListener;
            _channel.getState().onWritePossible();
        }
        else
            throw new IllegalStateException();
    }

    /**
     * @see javax.servlet.ServletOutputStream#isReady()
     */
    @Override
    public boolean isReady()
    {
        while (true)
        {
            switch(_state.get())
            {
                case OPEN:
                    return true;
                case ASYNC:
                    if (!_state.compareAndSet(OutputState.ASYNC, OutputState.READY))
                        continue;
                    return true;
                case READY:
                    return true;
                case PENDING:
                    if (!_state.compareAndSet(OutputState.PENDING, OutputState.UNREADY))
                        continue;
                    return false;
                case UNREADY:
                    return false;

                case ERROR:
                    return true;
                    
                case CLOSED:
                    return true;
            }
        }
    }

    @Override
    public void run()
    {
        loop: while (true)
        {
            OutputState state = _state.get();

            if(_onError!=null)
            {
                switch(state)
                {
                    case CLOSED:
                    case ERROR:
                        _onError=null;
                        break loop;

                    default:
                        if (_state.compareAndSet(state, OutputState.ERROR))
                        {
                            Throwable th=_onError;
                            _onError=null;
                            if (LOG.isDebugEnabled())
                                LOG.debug("onError",th);
                            _writeListener.onError(th);
                            close();

                            break loop;
                        }

                }
                continue;
            }

            switch(_state.get())
            {
                case READY:
                case CLOSED:
                    // even though a write is not possible, because a close has 
                    // occurred, we need to call onWritePossible to tell async
                    // producer that the last write completed.
                    try
                    {
                        _writeListener.onWritePossible();
                        break loop;
                    }
                    catch (Throwable e)
                    {
                        _onError=e;
                    }
                    break;
                default:

            }
        }
    }
    
    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}",this.getClass().getSimpleName(),hashCode(),_state.get());
    }
    
    private abstract class AsyncICB extends IteratingCallback
    {
        @Override
        protected void onCompleteSuccess()
        {
            while(true)
            {
                OutputState last=_state.get();
                switch(last)
                {
                    case PENDING:
                        if (!_state.compareAndSet(OutputState.PENDING, OutputState.ASYNC))
                            continue;
                        break;

                    case UNREADY:
                        if (!_state.compareAndSet(OutputState.UNREADY, OutputState.READY))
                            continue;
                        _channel.getState().onWritePossible();
                        break;

                    case CLOSED:
                        break;

                    default:
                        throw new IllegalStateException();
                }
                break;
            }
        }

        @Override
        public void onCompleteFailure(Throwable e)
        {
            _onError=e;
            _channel.getState().onWritePossible();
        }
    }
    
    
    private class AsyncFlush extends AsyncICB
    {
        protected volatile boolean _flushed;

        public AsyncFlush()
        {
        }

        @Override
        protected Action process()
        {
            if (BufferUtil.hasContent(_aggregate))
            {
                _flushed=true;
                write(_aggregate, false, this);
                return Action.SCHEDULED;
            }

            if (!_flushed)
            {
                _flushed=true;
                write(BufferUtil.EMPTY_BUFFER,false,this);
                return Action.SCHEDULED;
            }

            return Action.SUCCEEDED;
        }
    }



    private class AsyncWrite extends AsyncICB
    {
        private final ByteBuffer _buffer;
        private final ByteBuffer _slice;
        private final boolean _complete;
        private final int _len;
        protected volatile boolean _completed;

        public AsyncWrite(byte[] b, int off, int len, boolean complete)
        {
            _buffer=ByteBuffer.wrap(b, off, len);
            _len=len;
            // always use a view for large byte arrays to avoid JVM pooling large direct buffers
            _slice=_len<getBufferSize()?null:_buffer.duplicate();
            _complete=complete;
        }

        public AsyncWrite(ByteBuffer buffer, boolean complete)
        {
            _buffer=buffer;
            _len=buffer.remaining();
            // Use a slice buffer for large indirect to avoid JVM pooling large direct buffers
            _slice=_buffer.isDirect()||_len<getBufferSize()?null:_buffer.duplicate();
            _complete=complete;
        }

        @Override
        protected Action process()
        {
            // flush any content from the aggregate
            if (BufferUtil.hasContent(_aggregate))
            {
                _completed=_len==0;
                write(_aggregate, _complete && _completed, this);
                return Action.SCHEDULED;
            }

            // Can we just aggregate the remainder?
            if (!_complete && _len<BufferUtil.space(_aggregate) && _len<_commitSize)
            {
                int position = BufferUtil.flipToFill(_aggregate);
                BufferUtil.put(_buffer,_aggregate);
                BufferUtil.flipToFlush(_aggregate, position);
                return Action.SUCCEEDED;
            }
            
            // Is there data left to write?
            if (_buffer.hasRemaining())
            {
                // if there is no slice, just write it
                if (_slice==null)
                {
                    _completed=true;
                    write(_buffer, _complete, this);
                    return Action.SCHEDULED;
                }
                
                // otherwise take a slice
                int p=_buffer.position();
                int l=Math.min(getBufferSize(),_buffer.remaining());
                int pl=p+l;
                _slice.limit(pl);
                _buffer.position(pl);
                _slice.position(p);
                _completed=!_buffer.hasRemaining();
                write(_slice, _complete && _completed, this);
                return Action.SCHEDULED;
            }
            
            // all content written, but if we have not yet signal completion, we
            // need to do so
            if (_complete && !_completed)
            {
                _completed=true;
                write(BufferUtil.EMPTY_BUFFER, _complete, this);
                return Action.SCHEDULED;
            }

            return Action.SUCCEEDED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            super.onCompleteSuccess();
            if (_complete)
                closed();
        }
        
        
    }


    /* ------------------------------------------------------------ */
    /** An iterating callback that will take content from an
     * InputStream and write it to the associated {@link HttpChannel}.
     * A non direct buffer of size {@link HttpOutput#getBufferSize()} is used.
     * This callback is passed to the {@link HttpChannel#write(ByteBuffer, boolean, Callback)} to
     * be notified as each buffer is written and only once all the input is consumed will the
     * wrapped {@link Callback#succeeded()} method be called.
     */
    private class InputStreamWritingCB extends IteratingNestedCallback
    {
        private final InputStream _in;
        private final ByteBuffer _buffer;
        private boolean _eof;

        public InputStreamWritingCB(InputStream in, Callback callback)
        {
            super(callback);
            _in=in;
            _buffer = _channel.getByteBufferPool().acquire(getBufferSize(), false);
        }

        @Override
        protected Action process() throws Exception
        {
            // Only return if EOF has previously been read and thus
            // a write done with EOF=true
            if (_eof)
            {
                // Handle EOF
                _in.close();
                closed();
                _channel.getByteBufferPool().release(_buffer);
                return Action.SUCCEEDED;
            }
            
            // Read until buffer full or EOF
            int len=0;
            while (len<_buffer.capacity() && !_eof)
            {
                int r=_in.read(_buffer.array(),_buffer.arrayOffset()+len,_buffer.capacity()-len);
                if (r<0)
                    _eof=true;
                else
                    len+=r;
            }

            // write what we have
            _buffer.position(0);
            _buffer.limit(len);
            write(_buffer,_eof,this);
            return Action.SCHEDULED;
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            super.onCompleteFailure(x);
            _channel.getByteBufferPool().release(_buffer);
            try
            {
                _in.close();
            }
            catch (IOException e)
            {
                LOG.ignore(e);
            }
        }

    }

    /* ------------------------------------------------------------ */
    /** An iterating callback that will take content from a
     * ReadableByteChannel and write it to the {@link HttpChannel}.
     * A {@link ByteBuffer} of size {@link HttpOutput#getBufferSize()} is used that will be direct if
     * {@link HttpChannel#useDirectBuffers()} is true.
     * This callback is passed to the {@link HttpChannel#write(ByteBuffer, boolean, Callback)} to
     * be notified as each buffer is written and only once all the input is consumed will the
     * wrapped {@link Callback#succeeded()} method be called.
     */
    private class ReadableByteChannelWritingCB extends IteratingNestedCallback
    {
        private final ReadableByteChannel _in;
        private final ByteBuffer _buffer;
        private boolean _eof;

        public ReadableByteChannelWritingCB(ReadableByteChannel in, Callback callback)
        {
            super(callback);
            _in=in;
            _buffer = _channel.getByteBufferPool().acquire(getBufferSize(), _channel.useDirectBuffers());
        }

        @Override
        protected Action process() throws Exception
        {
            // Only return if EOF has previously been read and thus
            // a write done with EOF=true
            if (_eof)
            {
                _in.close();
                closed();
                _channel.getByteBufferPool().release(_buffer);
                return Action.SUCCEEDED;
            }
            
            // Read from stream until buffer full or EOF
            _buffer.clear();
            while (_buffer.hasRemaining() && !_eof)
              _eof = (_in.read(_buffer)) <  0;

            // write what we have
            _buffer.flip();
            write(_buffer,_eof,this);

            return Action.SCHEDULED;
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            super.onCompleteFailure(x);
            _channel.getByteBufferPool().release(_buffer);
            try
            {
                _in.close();
            }
            catch (IOException e)
            {
                LOG.ignore(e);
            }
        }
    }
}
