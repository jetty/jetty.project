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

/**
 * <p>Specialized {@link DataInfo} for {@link ByteBuffer} content.</p>
 */
public class ByteBufferDataInfo extends DataInfo
{
    private final ByteBuffer buffer;
    private final int length;

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close)
    {
        this(buffer, close, false);
    }

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close, boolean compress)
    {
        super(close, compress);
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
}
