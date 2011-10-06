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

import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.Buffer;

public class HttpInput extends ServletInputStream
{
    protected final HttpConnection _connection;
    protected final HttpParser _parser;
    
    /* ------------------------------------------------------------ */
    public HttpInput(HttpConnection connection)
    {
        _connection=connection;
        _parser=(HttpParser)connection.getParser();
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.InputStream#read()
     */
    @Override
    public int read() throws IOException
    {
        int c=-1;
        Buffer content=_parser.blockForContent(_connection.getMaxIdleTime());
        if (content!=null)
            c= 0xff & content.get();
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
        Buffer content=_parser.blockForContent(_connection.getMaxIdleTime());
        if (content!=null)
            l= content.get(b, off, len);
        return l;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int available() throws IOException
    {
        return _parser.available();
    }
    
    
    

}
