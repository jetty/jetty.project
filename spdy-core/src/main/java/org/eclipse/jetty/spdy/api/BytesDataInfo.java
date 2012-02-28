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
    private byte[] bytes;
    private int offset;

    public BytesDataInfo(byte[] bytes, boolean close)
    {
        this(bytes, close, false);
    }

    public BytesDataInfo(byte[] bytes, boolean close, boolean compress)
    {
        super(close, compress);
        this.bytes = bytes;
    }

    @Override
    public int length()
    {
        return bytes.length;
    }

    @Override
    public int available()
    {
        return length() - offset;
    }

    @Override
    public int readInto(ByteBuffer output)
    {
        int space = output.remaining();
        int length = Math.min(available(), space);
        output.put(bytes, offset, length);
        offset += length;
        return length;
    }
}
