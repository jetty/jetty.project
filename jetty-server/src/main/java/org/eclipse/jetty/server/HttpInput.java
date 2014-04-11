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

import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EofException;

public class HttpInput extends ServletInputStream
{
    protected final AbstractHttpConnection _connection;
    protected final HttpParser _parser;

    /* ------------------------------------------------------------ */
    public HttpInput(AbstractHttpConnection connection)
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
        byte[] bytes = new byte[1];
        int read = read(bytes, 0, 1);
        return read < 0 ? -1 : 0xff & bytes[0];
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
        else if (_connection.isEarlyEOF())
            throw new EofException("early EOF");
        return l;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int available() throws IOException
    {
        return _parser.available();
    }
}
