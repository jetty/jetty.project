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
import java.io.Writer;

import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
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
    protected final AbstractHttpConnection _connection;
    protected final AbstractGenerator _generator;
    private boolean _closed;
    private ByteArrayBuffer _onebyte;
    
    // These are held here for reuse by Writer
    String _characterEncoding;
    Writer _converter;
    char[] _chars;
    ByteArrayOutputStream2 _bytes;

    /* ------------------------------------------------------------ */
    public HttpOutput(AbstractHttpConnection connection)
    {
        _connection=connection;
        _generator=(AbstractGenerator)connection.getGenerator();
    }

    /* ------------------------------------------------------------ */
    public int getMaxIdleTime()
    {
        return _connection.getMaxIdleTime();
    }
    
    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _generator.getContentWritten()>0;
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
        _generator.flush(getMaxIdleTime());
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        write(new ByteArrayBuffer(b,off,len));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#write(byte[])
     */
    @Override
    public void write(byte[] b) throws IOException
    {
        write(new ByteArrayBuffer(b));
    }

    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.OutputStream#write(int)
     */
    @Override
    public void write(int b) throws IOException
    {
        if (_onebyte==null)
            _onebyte=new ByteArrayBuffer(1);
        else
            _onebyte.clear();
        _onebyte.put((byte)b);
        write(_onebyte);
    }

    /* ------------------------------------------------------------ */
    private void write(Buffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("Closed");
        if (!_generator.isOpen())
            throw new EofException();
        
        // Block until we can add _content.
        while (_generator.isBufferFull())
        {
            _generator.blockForOutput(getMaxIdleTime());
            if (_closed)
                throw new IOException("Closed");
            if (!_generator.isOpen())
                throw new EofException();
        }

        // Add the _content
        _generator.addContent(buffer, Generator.MORE);

        // Have to flush and complete headers?
        
        if (_generator.isAllContentWritten())
        {
            flush();
            close();
        } 
        else if (_generator.isBufferFull())
            _connection.commitResponse(Generator.MORE);

        // Block until our buffer is free
        while (buffer.length() > 0 && _generator.isOpen())
        {
            _generator.blockForOutput(getMaxIdleTime());
        }
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
