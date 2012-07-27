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
package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

/**
 * Support class for reading binary message data as an InputStream.
 */
public class MessageInputStream extends InputStream implements StreamAppender
{
    private final ByteBuffer buffer;

    public MessageInputStream(ByteBuffer buf)
    {
        BufferUtil.clearToFill(buf);
        this.buffer = buf;
    }

    @Override
    public void appendBuffer(ByteBuffer buf)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void bufferComplete() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public ByteBuffer getBuffer()
    {
        return buffer;
    }

    @Override
    public int read() throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }
}
