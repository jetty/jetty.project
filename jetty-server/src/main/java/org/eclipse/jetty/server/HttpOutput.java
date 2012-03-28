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
    private final HttpTransport _transport;
    private boolean _closed;
    
    // These are held here for reuse by Writer
    String _characterEncoding;
    Writer _converter;
    char[] _chars;
    ByteArrayOutputStream2 _bytes;

    /* ------------------------------------------------------------ */
    public HttpOutput(HttpTransport transport)
    {
        _transport=transport;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _transport.getContentWritten()>0;
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#close()
     */
    @Override
    public void close() throws IOException
    {
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
        _transport.flushResponse();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (_closed)
            throw new IOException("Closed");

        _transport.write(ByteBuffer.wrap(b,off,len),true);
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

        _transport.write(ByteBuffer.wrap(b),true);
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

        _transport.write(ByteBuffer.wrap(new byte[]{(byte)b}),true);
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
