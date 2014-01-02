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

package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * <p>Specialized {@link DataInfo} for {@link ByteBuffer} content.</p>
 */
public class ByteBufferDataInfo extends DataInfo
{
    private final ByteBuffer buffer;
    private final int length;

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close)
    {
        this(0, TimeUnit.SECONDS, buffer, close);
    }

    public ByteBufferDataInfo(long timeout, TimeUnit unit, ByteBuffer buffer, boolean close)
    {
        super(timeout, unit, close);
        this.buffer = buffer;
        this.length = buffer.remaining();
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public int available()
    {
        return buffer.remaining();
    }

    @Override
    public int readInto(ByteBuffer output)
    {
        int space = output.remaining();
        if (available() > space)
        {
            int limit = buffer.limit();
            buffer.limit(buffer.position() + space);
            output.put(buffer);
            buffer.limit(limit);
        }
        else
        {
            space = buffer.remaining();
            output.put(buffer);
        }
        return space;
    }

    @Override
    public int readInto(byte[] bytes, int offset, int length)
    {
        int available = available();
        if (available < length)
            length = available;
        buffer.get(bytes, offset, length);
        return length;
    }

    @Override
    protected ByteBuffer allocate(int size)
    {
        return buffer.isDirect() ? ByteBuffer.allocateDirect(size) : super.allocate(size);
    }
}
