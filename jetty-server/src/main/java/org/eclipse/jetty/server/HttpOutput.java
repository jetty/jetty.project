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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.TimeoutException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

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
public class HttpOutput extends ServletOutputStream
{
    private static Logger LOG = Log.getLogger(HttpOutput.class);
    private final HttpChannel<?> _channel;
    private boolean _closed;
    private long _written;
    private ByteBuffer _aggregate;
    private int _bufferSize;

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
        _closed = false;
    }

    /** Called by the HttpChannel if the output was closed
     * externally (eg by a 500 exception handling).
     */
    void closed()
    {
        _closed = true;
        releaseBuffer();
    }

    @Override
    public void close()
    {
        if (!isClosed())
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
                _channel.getEndPoint().shutdownOutput();
                LOG.ignore(e);
            }
        }
        closed();
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
        return _closed;
    }

    @Override
    public void flush() throws IOException
    {
        if (isClosed())
            return;

        if (BufferUtil.hasContent(_aggregate))
            _channel.write(_aggregate, false);
        else
            _channel.write(BufferUtil.EMPTY_BUFFER, false);
    }

    public boolean closeIfAllContentWritten() throws IOException
    {
        return _channel.getResponse().closeIfAllContentWritten(_written);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (isClosed())
            throw new EOFException("Closed");

        // Do we have an aggregate buffer already ?
        if (_aggregate == null)
        {
            // What size should the aggregate be ?
            int size = getBufferSize();

            // If this write would fill more than half the aggregate, just write it directly
            if (len > size / 2)
            {
                _channel.write(ByteBuffer.wrap(b, off, len), false);
                _written += len;
                return;
            }

            // Allocate an aggregate buffer.
            // Never direct as it is slow to do little writes to a direct buffer.
            _aggregate = _channel.getByteBufferPool().acquire(size, false);
        }

        // Do we have space to aggregate ?
        int space = BufferUtil.space(_aggregate);
        if (len > space)
        {
            // No space so write the aggregate out if it is not empty
            if (BufferUtil.hasContent(_aggregate))
            {
                _channel.write(_aggregate, false);
                space = BufferUtil.space(_aggregate);
            }
        }

        // Do we have space to aggregate now ?
        if (len > space)
        {
            // No space so write the content directly
            _channel.write(ByteBuffer.wrap(b, off, len), false);
            _written += len;
            return;
        }

        // Aggregate the content
        BufferUtil.append(_aggregate, b, off, len);
        _written += len;

        // Check if all written or full
        if (!closeIfAllContentWritten() && BufferUtil.isFull(_aggregate))
            _channel.write(_aggregate, false);
    }


    @Override
    public void write(int b) throws IOException
    {
        if (isClosed())
            throw new EOFException("Closed");

        // Allocate an aggregate buffer.
        // Never direct as it is slow to do little writes to a direct buffer.
        if (_aggregate == null)
            _aggregate = _channel.getByteBufferPool().acquire(getBufferSize(), false);

        BufferUtil.append(_aggregate, (byte)b);
        _written++;

        // Check if all written or full
        if (!closeIfAllContentWritten() && BufferUtil.isFull(_aggregate))
            _channel.write(_aggregate, false);
    }

    @Override
    public void print(String s) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");

        write(s.getBytes(_channel.getResponse().getCharacterEncoding()));
    }

    @Deprecated
    public void sendContent(Object content) throws IOException
    {
        final BlockingCallback callback =_channel.getWriteBlockingCallback();

        if (content instanceof HttpContent)
        {
            _channel.getResponse().setHeaders((HttpContent)content);
            sendContent((HttpContent)content,callback);
        }
        else if (content instanceof Resource)
        {
            Resource resource = (Resource)content;
            _channel.getResponse().getHttpFields().putDateField(HttpHeader.LAST_MODIFIED, resource.lastModified());
            
            ReadableByteChannel in=((Resource)content).getReadableByteChannel();
            if (in!=null)
                sendContent(in,callback);
            else
                sendContent(resource.getInputStream(),callback);
        }
        else if (content instanceof ByteBuffer)
        {
            sendContent((ByteBuffer)content,callback);
        }
        else if (content instanceof ReadableByteChannel)
        {
            sendContent((ReadableByteChannel)content,callback);
        }
        else if (content instanceof InputStream)
        {
            sendContent((InputStream)content,callback);
        }
        else
            callback.failed(new IllegalArgumentException("unknown content type "+content.getClass()));

        try
        {
            callback.block();
        }
        catch (InterruptedException | TimeoutException e)
        {
            throw new IOException(e);
        }
    }
    
    
    public void sendContent(HttpContent content) throws IOException
    {
        try
        {
            final BlockingCallback callback =_channel.getWriteBlockingCallback();
            sendContent(content,callback);
            callback.block();
        }
        catch (InterruptedException | TimeoutException e)
        {
            throw new IOException(e);
        }
    }

    public void sendContent(ByteBuffer content, Callback callback)
    {
        _channel.write(content,true,callback);
    }

    public void sendContent(InputStream in, Callback callback)
    {
        new InputStreamWritingCB(in,callback).iterate();
    }

    public void sendContent(ReadableByteChannel in, Callback callback)
    {
        new ReadableByteChannelWritingCB(in,callback).iterate();
    }
    
    public void sendContent(HttpContent httpContent, Callback callback) throws IOException
    {
        if (isClosed())
            throw new IOException("Closed");
        if (BufferUtil.hasContent(_aggregate))
            throw new IOException("written");
        if (_channel.isCommitted())
            throw new IOException("committed");
            
        _closed=true;

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
    
    private class InputStreamWritingCB extends IteratingCallback
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
            int len=_in.read(_buffer.array(),0,_buffer.capacity());
            if (len==-1)
            {
                _channel.getByteBufferPool().release(_buffer);
                return true;
            }
            boolean eof=false;

            // if we read less than a buffer, are we at EOF?
            if (len<_buffer.capacity())
            {
                int len2=_in.read(_buffer.array(),len,_buffer.capacity()-len);
                if (len2<0)
                    eof=true;
                else
                    len+=len2;
            }

            _buffer.position(0);
            _buffer.limit(len);
            _channel.write(_buffer,eof,this);
            return false;
        }

        @Override
        public void failed(Throwable x)
        {
            super.failed(x);
            _channel.getByteBufferPool().release(_buffer);
        }
        
    }
    
    private class ReadableByteChannelWritingCB extends IteratingCallback
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
            int len=_in.read(_buffer);
            if (len==-1)
            {
                _channel.getByteBufferPool().release(_buffer);
                return true;
            }

            boolean eof=false;

            // if we read less than a buffer, are we at EOF?
            if (len<_buffer.capacity())
            {
                int len2=_in.read(_buffer);
                if (len2<0)
                    eof=true;
                else
                    len+=len2;
            }

            _buffer.flip();
            _channel.write(_buffer,eof,this);
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
