//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.io.Writer;
import java.nio.ByteBuffer;

import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;

/** Output.
 * 
 * <p>
 * Implements  {@link javax.servlet.ServletOutputStream} from the <code>javax.servlet</code> package.   
 * </p>
 * A {@link ServletOutputStream} implementation that writes content
 * to a {@link AbstractGenerator}.   The class is designed to be reused
 * and can be reopened after a close.
 */
public class HttpOutput extends ServletOutputStream 
{
    private final HttpChannel _channel;
    private boolean _closed;
    private long _written;
    private ByteBuffer _aggregate;

    /* ------------------------------------------------------------ */
    public HttpOutput(HttpChannel channel)
    {
        _channel=channel;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _written>0;
    }

    /* ------------------------------------------------------------ */
    public long getWritten()
    {
        return _written;
    }
    
    /* ------------------------------------------------------------ */
    public void reset()
    {
        _written=0;
        _closed=false;
    }
    
    /* ------------------------------------------------------------ */
    public void reopen()
    {
        _closed=false;
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#close()
     */
    @Override
    public void close() throws IOException
    {
        if (!_closed)
        {
            if (BufferUtil.hasContent(_aggregate))
                _channel.write(_aggregate,!_channel.getResponse().isIncluding());
            else 
                _channel.write(BufferUtil.EMPTY_BUFFER,!_channel.getResponse().isIncluding());
        }
        _closed=true;
        if (_aggregate!=null)
        {
            _channel.getConnector().getByteBufferPool().release(_aggregate);
            _aggregate=null;
        }
    }
    
    /* ------------------------------------------------------------ */
    public boolean isClosed()
    {
        return _closed;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void flush() throws IOException
    {
        if (_closed)
            throw new EofException();
        
        if (BufferUtil.hasContent(_aggregate))
            _channel.write(_aggregate,false);
        else 
            _channel.write(BufferUtil.EMPTY_BUFFER,false);
    }

    /* ------------------------------------------------------------ */
    public boolean checkAllWritten()
    {
        return _channel.getResponse().checkAllContentWritten(_written);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (_closed)
            throw new EofException();
        
        // Do we have an aggregate buffer already
        if (_aggregate==null)
        {
            // what size should the aggregate be?
            int size=_channel.getHttpConfiguration().getResponseBufferSize();
            
            // if this write would fill more than half the aggregate, just write it directory
            if (len>size/2)
            {
                _channel.write(ByteBuffer.wrap(b,off,len),false);
                _written+=len;
                return;
            }
            
            // allocate an aggregate buffer
            _aggregate=_channel.getConnector().getByteBufferPool().acquire(size,false);
        }
        
        // Do we have space to aggregate?
        int space = BufferUtil.space(_aggregate);
        if (len>space)
        {
            // No space so write the aggregate out if it is not empty
            if (BufferUtil.hasContent(_aggregate))
            {
                _channel.write(_aggregate,false);
                space=BufferUtil.space(_aggregate);
            }
        }

        // Do we now have space to aggregate?
        if (len>space)
        {
            // No space so write the content directly
            _channel.write(ByteBuffer.wrap(b,off,len),false);
            _written+=len;
            return;
        }
        
        // aggregate the content
        BufferUtil.append(_aggregate,b,off,len);

        // Check if all written or full
        if (!checkAllWritten() && BufferUtil.isFull(_aggregate))
            _channel.write(_aggregate,false);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#write(int)
     */
    @Override
    public void write(int b) throws IOException
    {
        if (_closed)
            throw new IOException("Closed");

        if (_aggregate==null)
            _aggregate=_channel.getConnector().getByteBufferPool().acquire(_channel.getHttpConfiguration().getResponseBufferSize(),false);
        
        BufferUtil.append(_aggregate,(byte)b);
        _written++;

        // Check if all written or full
        if (!checkAllWritten() && BufferUtil.isFull(_aggregate))
            _channel.write(_aggregate,false);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.ServletOutputStream#print(java.lang.String)
     */
    @Override
    public void print(String s) throws IOException
    {
        write(s.getBytes());
    }

    /* ------------------------------------------------------------ */
    public void sendContent(Object content) throws IOException
    {
        throw new IllegalStateException("Not implemented");
    }
    
    /* ------------------------------------------------------------ */
    public int getContentBufferSize()
    {
        if (_aggregate!=null)
            return _aggregate.capacity();
        return _channel.getHttpConfiguration().getResponseBufferSize();
    }

    /* ------------------------------------------------------------ */
    public void increaseContentBufferSize(int size)
    {
        if (_aggregate==null || size<=getContentBufferSize())
            return;

        ByteBuffer r=_channel.getConnector().getByteBufferPool().acquire(size,false);
        if (BufferUtil.hasContent(_aggregate))
            BufferUtil.flipPutFlip(_aggregate,r);
        if (_aggregate!=null)
            _channel.getConnector().getByteBufferPool().release(_aggregate);
        _aggregate=r;
    }

    /* ------------------------------------------------------------ */
    public void resetBuffer()
    {
        if (BufferUtil.hasContent(_aggregate))
            BufferUtil.clear(_aggregate);
    }


}
