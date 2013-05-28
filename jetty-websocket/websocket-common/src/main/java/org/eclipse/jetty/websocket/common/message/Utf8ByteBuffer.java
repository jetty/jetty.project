//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8Appendable;

public class Utf8ByteBuffer extends Utf8Appendable
{
    public static Utf8ByteBuffer wrap(ByteBuffer buffer)
    {
        AppendableByteBuffer abuffer = new AppendableByteBuffer(buffer);
        return new Utf8ByteBuffer(abuffer);
    }

    private final AppendableByteBuffer abb;

    public Utf8ByteBuffer(AppendableByteBuffer buffer)
    {
        super(buffer);
        this.abb = buffer;
    }

    public void append(char[] cbuf, int offset, int size)
    {
        int start = offset;
        int end = offset + size;
        for (int i = start; i < end; i++)
        {
            append((byte)(cbuf[i] & 0xFF));
        }
    }

    public void clear()
    {
        abb.clear();
    }

    public ByteBuffer getBuffer()
    {
        return abb.getBufferSlice();
    }

    @Override
    public int length()
    {
        return abb.remaining();
    }
}
