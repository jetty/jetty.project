/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.ByteBufferPool;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.frames.DataFrame;

public class DataFrameGenerator
{
    private final ByteBufferPool bufferPool;

    public DataFrameGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public ByteBuffer generate(int streamId, int length, DataInfo dataInfo)
    {
        ByteBuffer buffer = bufferPool.acquire(DataFrame.HEADER_LENGTH + length, true);
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
