// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;

import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.io.EofException;
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
    
    // These are held here for reuse by Writer
    String _characterEncoding;
    Writer _converter;
    char[] _chars;
    ByteArrayOutputStream2 _bytes;
    long _written;

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
    /*
     * @see java.io.OutputStream#close()
     */
    @Override
    public void close() throws IOException
    {
        if (!_closed)
            _channel.completeResponse();
        _closed=true;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isClosed()
    {
        return _closed;
    }
    
    /* ------------------------------------------------------------ */
    public void reopen()
    {
        _closed=false;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void flush() throws IOException
    {
        _channel.flushResponse();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (_closed)
            throw new EofException();

        _written+=_channel.write(ByteBuffer.wrap(b,off,len));
        _channel.getResponse().checkAllContentWritten(_written);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#write(byte[])
     */
    @Override
    public void write(byte[] b) throws IOException
    {
        if (_closed)
            throw new IOException("Closed");

        _written+=_channel.write(ByteBuffer.wrap(b));
        _channel.getResponse().checkAllContentWritten(_written);
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

        _written+=_channel.write(ByteBuffer.wrap(new byte[]{(byte)b}));
        _channel.getResponse().checkAllContentWritten(_written);
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

}
