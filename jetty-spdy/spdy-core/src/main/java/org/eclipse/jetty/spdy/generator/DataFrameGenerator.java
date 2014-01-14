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

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.util.BufferUtil;

public class DataFrameGenerator
{
    private final ByteBufferPool bufferPool;

    public DataFrameGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public ByteBuffer generate(int streamId, int length, DataInfo dataInfo)
    {
        ByteBuffer buffer = bufferPool.acquire(DataFrame.HEADER_LENGTH + length, Generator.useDirectBuffers);
        BufferUtil.clearToFill(buffer);
        buffer.limit(length + DataFrame.HEADER_LENGTH);
        buffer.position(DataFrame.HEADER_LENGTH);
        // Guaranteed to always be >= 0
        int read = dataInfo.readInto(buffer);

        buffer.putInt(0, streamId & 0x7F_FF_FF_FF);
        buffer.putInt(4, read & 0x00_FF_FF_FF);

        byte flags = dataInfo.getFlags();
        if (dataInfo.available() > 0)
            flags &= ~DataInfo.FLAG_CLOSE;
        buffer.put(4, flags);

        buffer.flip();
        return buffer;
    }
}
