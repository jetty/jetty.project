// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;

import javax.servlet.ServletInputStream;

import org.eclipse.jetty.util.BufferUtil;


public class HttpInput extends ServletInputStream
{
    protected final HttpChannel _connection;
    private ByteBuffer _content;
    
    /* ------------------------------------------------------------ */
    public HttpInput(HttpChannel connection)
    {
        _connection=connection;
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.InputStream#read()
     */
    @Override
    public int read() throws IOException
    {
        int c=-1;
        if (BufferUtil.isEmpty(_content))
            _content=_connection.blockForContent();
        if (BufferUtil.hasContent(_content))
            c= 0xff & _content.get();
        return c;
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see java.io.InputStream#read(byte[], int, int)
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        int l=-1;
        if (BufferUtil.isEmpty(_content))
            _content=_connection.blockForContent();
        if (BufferUtil.hasContent(_content))
        {
            l=Math.min(len,_content.remaining());
            _content.get(b,off,l);
        }
        return l;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int available() throws IOException
    {
        if (BufferUtil.isEmpty(_content))
            _content=_connection.getContent();
        if (BufferUtil.hasContent(_content))
            return _content.remaining();
        return 0;
    }
    
    
    

}
