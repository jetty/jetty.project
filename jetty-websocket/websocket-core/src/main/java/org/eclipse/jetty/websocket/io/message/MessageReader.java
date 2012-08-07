// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.io.message;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

/**
 * Support class for reading text message data as an Reader.
 * <p>
 * Due to the spec, this reader is forced to use the UTF8 charset.
 */
public class MessageReader extends Reader implements MessageAppender
{
    private ByteBuffer buffer;

    public MessageReader(ByteBuffer buf)
    {
        BufferUtil.clearToFill(buf);
        this.buffer = buf;
    }

    @Override
    public void appendMessage(ByteBuffer byteBuffer) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void close() throws IOException
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void messageComplete() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }
}
