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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

/**
 * Wrap a {@link ByteBuffer} to support {@link Appendable}.
 * <p>
 * Used by {@link Utf8ByteBuffer}
 */
public class AppendableByteBuffer implements Appendable
{
    private final ByteBuffer buffer;

    public AppendableByteBuffer(ByteBuffer buffer)
    {
        this.buffer = buffer;
    }

    @Override
    public Appendable append(char c) throws IOException
    {
        buffer.put((byte)c);
        return this;
    }

    @Override
    public Appendable append(CharSequence csq) throws IOException
    {
        if (csq == null)
        {
            append("null");
            return this;
        }
        int len = csq.length();
        return this.append(csq,0,len);
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) throws IOException
    {
        if (csq == null)
        {
            append("null");
            return this;
        }

        for (int idx = start; idx < end; idx++)
        {
            buffer.put((byte)csq.charAt(idx));
        }
        return this;
    }

    /**
     * Clear the buffer data, reset to position 0, ready to fill with more data.
     */
    public void clear()
    {
        BufferUtil.clearToFill(buffer);
    }

    /**
     * Get a {@link ByteBuffer#slice()} view of the buffer data.
     * 
     * @return the buffer data, in slice form.
     */
    public ByteBuffer getBufferSlice()
    {
        BufferUtil.flipToFlush(buffer,0);
        ByteBuffer slice = buffer.slice();
        BufferUtil.flipToFill(buffer);
        return slice;
    }

    public int remaining()
    {
        return buffer.remaining();
    }
}
