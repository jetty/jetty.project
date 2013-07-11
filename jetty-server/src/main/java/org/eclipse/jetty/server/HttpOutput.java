//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.IteratingNestedCallback;
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
    private long _written;
    private ByteBuffer _aggregate;
    private int _bufferSize;
    private WriteListener _writeListener;
    private volatile Throwable _onError;
    
    /* 
ACTION             OPEN       ASYNC      READY      PENDING       UNREADY                    
-------------------------------------------------------------------------------
setWriteListener() READY->owp ise        ise        ise           ise          
write()            OPEN       ise        PENDING    wpe           wpe          
flush()            OPEN       ise        PENDING    wpe           wpe
isReady()          OPEN:true  READY:true READY:true UNREADY:false UNREADY:false
write completed    -          -          -          ASYNC         READY->owp

     */
    enum State { OPEN, ASYNC, READY, PENDING, UNREADY, CLOSED }
    private final AtomicReference<State> _state=new AtomicReference<>(State.OPEN);
    
    

    public HttpOutput(HttpChannel<?> channel)
    {
        _channel = channel;
        _bufferSize = _channel.getHttpConfiguration().getOutputBufferSize();
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
        _state.set(State.OPEN);
    }

    public boolean isAllContentWritten()
    {
        return _channel.getResponse().isAllContentWritten(_written);
    }
    
    @Override
    public void close()
    {
        State state=_state.get();
        while(state!=State.CLOSED)
        {
            if (_state.compareAndSet(state,State.CLOSED))
            {
                try
                {
                    if (BufferUtil.hasContent(_aggregate))
                        _channel.write(_aggregate, !_channel.getResponse().isIncluding());
                    else
                        _channel.write(BufferUtil.EMPTY_BUFFER, !_channel.getResponse().isIncluding());
                }
                catch(IOException e)
                {
                    LOG.debug(e);
                    _channel.failed();
                }
                releaseBuffer();
                return;
            }
            state=_state.get();
        }
    }
    
    /* Called to indicated that the output is already closed and the state needs to be updated to match */
    void closed()
    {
        State state=_state.get();
        while(state!=State.CLOSED)
        {
            if (_state.compareAndSet(state,State.CLOSED))
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
            state=_state.get();
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
        return _state.get()==State.CLOSED;
    }

    @Override
    public void flush() throws IOException
    {
        while(true)
        {
            switch(_state.get())
            {
                case OPEN:
                    if (BufferUtil.hasContent(_aggregate))
                        _channel.write(_aggregate, false);
                    else
                        _channel.write(BufferUtil.EMPTY_BUFFER, false);
                    return;
                    
                case ASYNC:
                    throw new IllegalStateException("isReady() not called");
                    
                case READY:
                    if (!_state.compareAndSet(State.READY, State.PENDING))
                        continue;
                    new AsyncFlush().process();
                    return;
                    
                case PENDING:
                case UNREADY:
                    throw new WritePendingException();
                    
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
                    if (!_state.compareAndSet(State.READY, State.PENDING))
                        continue;

                    // Should we aggregate?
                    int capacity = getBufferSize();
                    if (!complete && len<=capacity/4)
                    {
                        if (_aggregate == null)
                            _aggregate = _channel.getByteBufferPool().acquire(capacity, false);

                        // YES - fill the aggregate with content from the buffer
                        int filled = BufferUtil.fill(_aggregate, b, off, len);

                        // return if we are not complete, not full and filled all the content
                        if (!complete && filled==len && !BufferUtil.isFull(_aggregate))
                        {
                            if (!_state.compareAndSet(State.PENDING, State.ASYNC))
                                throw new IllegalStateException();
                            return;
                        }

                        // adjust offset/length
                        off+=filled;
                        len-=filled;
                    }

                    // Do the asynchronous writing from the callback
                    new AsyncWrite(b,off,len,complete).process();
                    return;

    	        case PENDING:
                case UNREADY:
    	            throw new WritePendingException();

                case CLOSED:
                    throw new EofException("Closed");
    	    }
    	    break;
    	}

    	
    	// handle blocking write
    	
    	// Should we aggregate?
    	int capacity = getBufferSize();
    	if (!complete && len<=capacity/4)
    	{
    	    if (_aggregate == null)
    	        _aggregate = _channel.getByteBufferPool().acquire(capacity, false);

    	    // YES - fill the aggregate with content from the buffer
    	    int filled = BufferUtil.fill(_aggregate, b, off, len);

    	    // return if we are not complete, not full and filled all the content
    	    if (!complete && filled==len && !BufferUtil.isFull(_aggregate))
    	        return;

    	    // adjust offset/length
    	    off+=filled;
    	    len-=filled;
    	}

    	// flush any content from the aggregate
    	if (BufferUtil.hasContent(_aggregate))
    	{
    	    _channel.write(_aggregate, complete && len==0);

    	    // should we fill aggregate again from the buffer?
    	    if (len>0 && !complete && len<=_aggregate.capacity()/4)
    	    {
    	        BufferUtil.append(_aggregate, b, off, len);
    	        return;
    	    }
    	}

    	// write any remaining content in the buffer directly
    	if (len>0)
    	    _channel.write(ByteBuffer.wrap(b, off, len), complete);
        else if (complete)
            _channel.write(BufferUtil.EMPTY_BUFFER,complete);

    	if (complete)
    	{
    	    closed();
    	}

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
                        BlockingCallback callback = _channel.getWriteBlockingCallback();
                        _channel.write(_aggregate, complete, callback);
                        callback.block();
                        if (complete)
                            closed();
                    }
                    break;
                    
                case ASYNC:
                    throw new IllegalStateException("isReady() not called");

                case READY:
                    if (!_state.compareAndSet(State.READY, State.PENDING))
                        continue;

                    if (_aggregate == null)
                        _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), false);
                    BufferUtil.append(_aggregate, (byte)b);

                    // Check if all written or full
                    if (!complete && !BufferUtil.isFull(_aggregate))
                    {
                        if (!_state.compareAndSet(State.PENDING, State.ASYNC))
                            throw new IllegalStateException();
                        return;
                    }

                    // Do the asynchronous writing from the callback
                    new AsyncFlush().process();
                    return;

                case PENDING:
                case UNREADY:
                    throw new WritePendingException();

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
     * @param content The content to send
     * @throws IOException
     */
    public void sendContent(ByteBuffer content) throws IOException
    {
        final BlockingCallback callback =_channel.getWriteBlockingCallback();
        _channel.write(content,true,callback);
        callback.block();
    }

    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param in The content to send
     * @throws IOException
     */
    public void sendContent(InputStream in) throws IOException
    {
        final BlockingCallback callback =_channel.getWriteBlockingCallback();
        new InputStreamWritingCB(in,callback).iterate();
        callback.block();
    }

    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param in The content to send
     * @throws IOException
     */
    public void sendContent(ReadableByteChannel in) throws IOException
    {
        final BlockingCallback callback =_channel.getWriteBlockingCallback();
        new ReadableByteChannelWritingCB(in,callback).iterate();
        callback.block();
    }


    /* ------------------------------------------------------------ */
    /** Blocking send of content.
     * @param content The content to send
     * @throws IOException
     */
    public void sendContent(HttpContent content) throws IOException
    {
        final BlockingCallback callback =_channel.getWriteBlockingCallback();
        sendContent(content,callback);
        callback.block();
    }


    /* ------------------------------------------------------------ */
    /** Asynchronous send of content.
     * @param content The content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(ByteBuffer content, final Callback callback)
    {
        _channel.write(content,true,new Callback()
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
     * @param in The content to send
     * @param callback The callback to use to notify success or failure
     */
    public void sendContent(InputStream in, Callback callback)
    {
        new InputStreamWritingCB(in,callback).iterate();
    }

    /* ------------------------------------------------------------ */
    /** Asynchronous send of content.
     * @param in The content to send
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
                    if (!_state.compareAndSet(State.OPEN, State.PENDING))
                        continue;
                    break;
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
        this._bufferSize = size;
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
        
        if (_state.compareAndSet(State.OPEN, State.READY))
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
                    if (!_state.compareAndSet(State.ASYNC, State.READY))
                        continue;
                    return true;
                case READY:
                    return true;
                case PENDING:
                    if (!_state.compareAndSet(State.PENDING, State.UNREADY))
                        continue;
                    return false;
                case UNREADY:
                    return false;
                case CLOSED:
                    return false;
            }
        }
    }

    @Override
    public void run()
    {
        if(_onError!=null)
        {
            Throwable th=_onError;
            _onError=null;
            _writeListener.onError(th);
            close();
        }
        if (_state.get()==State.READY)
        {
            try
            {
                _writeListener.onWritePossible();
            }
            catch (IOException e)
            {
                _writeListener.onError(e);
                close();
            }
        }
    }

    private class AsyncWrite extends AsyncFlush
    {
        private final byte[] _b;
        private final int _off;
        private final int _len;
        private final boolean _complete;

        public AsyncWrite(byte[] b, int off, int len, boolean complete) 
        {
            _b=b;
            _off=off;
            _len=len;
            _complete=complete;
        }

        @Override
        protected boolean process() 
        {
            // flush any content from the aggregate
            if (BufferUtil.hasContent(_aggregate))
            {
                _channel.write(_aggregate, _complete && _len==0, this);
                return false;
            }

            // TODO write comments
            if (!_complete && _len<BufferUtil.space(_aggregate) && _len<_aggregate.capacity()/4)
            {
                BufferUtil.append(_aggregate, _b, _off, _len);
            }
            // TODO write comments
            else if (_len>0 && !_flushed)
            {
                ByteBuffer buffer=ByteBuffer.wrap(_b, _off, _len);
                _flushed=true;
                _channel.write(buffer, _complete, this);
                return false;
            }
            else if (_len==0 && !_flushed)
            {
                _flushed=true;
                _channel.write(BufferUtil.EMPTY_BUFFER, _complete, this);
                return false;
            }

            if (_complete)
                closed();
            return true;
        }
    }

    private class AsyncFlush extends IteratingCallback
    {
        protected boolean _flushed; 

        public AsyncFlush() 
        {
        }

        @Override
        protected boolean process()
        {
            if (BufferUtil.hasContent(_aggregate))
            {
                _flushed=true;
                _channel.write(_aggregate, false, this);
                return false;
            }

            if (!_flushed)
            {
                _flushed=true;
                _channel.write(BufferUtil.EMPTY_BUFFER,false,this);
                return false;
            }
            
            return true;
        }

        @Override
        protected void completed()
        {
            try
            {
                loop: while(true)
                {
                    State last=_state.get();
                    switch(last)
                    {
                        case PENDING:
                            if (!_state.compareAndSet(State.PENDING, State.ASYNC))
                                continue;
                            break;
                            
                        case UNREADY:
                            if (!_state.compareAndSet(State.UNREADY, State.READY))
                                continue;
                            _channel.getState().onWritePossible();
                            break;
                            
                        case CLOSED:
                            _onError=new EofException("Closed");
                            break;

                        default:
                            throw new IllegalStateException();
                    }

                    break loop;
                }
            }
            catch (Exception e)
            {
                _onError=e;
                _channel.getState().onWritePossible();
            }
        }
        
        @Override
        public void failed(Throwable e)
        {
            _onError=e;
            _channel.getState().onWritePossible();
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
        final InputStream _in;
        final ByteBuffer _buffer;

        public InputStreamWritingCB(InputStream in, Callback callback)
        {          
            super(callback);
            _in=in;
            _buffer = _channel.getByteBufferPool().acquire(getBufferSize(), false);
        }

        @Override
        protected boolean process() throws Exception
        {
            boolean eof=false;
            int len=_in.read(_buffer.array(),0,_buffer.capacity());
            
            if (len<0)
            {
                eof=true;
                len=0;
            }
            else if (len<_buffer.capacity())
            {
                // read ahead for EOF to try for single commit
                int len2=_in.read(_buffer.array(),len,_buffer.capacity()-len);
                if (len2<0)
                    eof=true;
                else
                    len+=len2;
            }
            
            // write what we have
            _buffer.position(0);
            _buffer.limit(len);
            _channel.write(_buffer,eof,this);
            
            // Handle EOF
            if (eof)
            {
                closed();
                _channel.getByteBufferPool().release(_buffer);
                return true;
            }

            return false;
        }

        @Override
        public void failed(Throwable x)
        {
            super.failed(x);
            _channel.getByteBufferPool().release(_buffer);
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
        final ReadableByteChannel _in;
        final ByteBuffer _buffer;

        public ReadableByteChannelWritingCB(ReadableByteChannel in, Callback callback)
        {          
            super(callback);
            _in=in;
            _buffer = _channel.getByteBufferPool().acquire(getBufferSize(), _channel.useDirectBuffers());
        }

        @Override
        protected boolean process() throws Exception
        {
            _buffer.clear();
            boolean eof=false;
            int len=_in.read(_buffer);
            
            if (len<0)
            {
                eof=true;
                len=0;
            }
            else if (len<_buffer.capacity())
            {
                // read ahead for EOF to try for single commit
                int len2=_in.read(_buffer);
                if (len2<0)
                    eof=true;
                else
                    len+=len2;
            }
            
            // write what we have
            _buffer.flip();
            _channel.write(_buffer,eof,this);
            
            // Handle EOF
            if (eof)
            {
                closed();
                _channel.getByteBufferPool().release(_buffer);
                return true;
            }

            return false;
        }

        @Override
        public void failed(Throwable x)
        {
            super.failed(x);
            _channel.getByteBufferPool().release(_buffer);
        }
    }

}
