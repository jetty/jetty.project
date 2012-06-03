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
 * <p>Specialized {@link DataInfo} for byte array content.</p>
 */
public class BytesDataInfo extends DataInfo
{
    private final byte[] bytes;
    private final int offset;
    private final int length;
    private int index;

    public BytesDataInfo(byte[] bytes, boolean close)
    {
        this(bytes, 0, bytes.length, close);
    }

    public BytesDataInfo(byte[] bytes, int offset, int length, boolean close)
    {
        super(close, false);
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
        this.index = offset;
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public int available()
    {
        return length - index + offset;
    }

    @Override
    public int readInto(ByteBuffer output)
    {
        int space = output.remaining();
        int chunk = Math.min(available(), space);
        output.put(bytes, index, chunk);
        index += chunk;
        return chunk;
    }
}
