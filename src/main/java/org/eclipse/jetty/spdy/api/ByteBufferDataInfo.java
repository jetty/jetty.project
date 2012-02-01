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

package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;

public class ByteBufferDataInfo extends DataInfo
{
    private ByteBuffer buffer;

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close)
    {
        this(buffer, close, false);
    }

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close, boolean compress)
    {
        super(close, compress);
        setByteBuffer(buffer);
    }

    @Override
    public int getBytesCount()
    {
        return buffer.remaining();
    }

    @Override
    public int getBytes(ByteBuffer output)
    {
        int length = output.remaining();
        if (buffer.remaining() > length)
        {
            int limit = buffer.limit();
            buffer.limit(buffer.position() + length);
            output.put(buffer);
            buffer.limit(limit);
        }
        else
        {
            length = buffer.remaining();
            output.put(buffer);
        }
        setConsumed(!buffer.hasRemaining());
        return length;
    }

    public void setByteBuffer(ByteBuffer buffer)
    {
        this.buffer = buffer;
    }
}
