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
    protected final HttpChannel _channel;
    protected final byte[] _byte=new byte[1];
    
    /* ------------------------------------------------------------ */
    public HttpInput(HttpChannel channel)
    {
        _channel=channel;
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see java.io.InputStream#read()
     */
    @Override
    public int read() throws IOException
    {
        int len=_channel.read(_byte,0,1);
        return len<0?len:_byte[0];
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see java.io.InputStream#read(byte[], int, int)
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        return _channel.read(_byte,0,1);
    }

    /* ------------------------------------------------------------ */
    @Override
    public int available() throws IOException
    {
        return _channel.available();
    }

}
