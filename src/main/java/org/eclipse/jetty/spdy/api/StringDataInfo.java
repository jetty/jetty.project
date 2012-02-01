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
import java.nio.charset.Charset;

public class StringDataInfo extends DataInfo
{
    private byte[] bytes;
    private int offset;

    public StringDataInfo(String string, boolean close)
    {
        this(string, close, false);
    }

    public StringDataInfo(String string, boolean close, boolean compress)
    {
        super(close, compress);
        setString(string);
    }

    @Override
    public int getBytesCount()
    {
        return bytes.length - offset;
    }

    @Override
    public int getBytes(ByteBuffer output)
    {
        int remaining = output.remaining();
        int length = Math.min(bytes.length - offset, remaining);
        output.put(bytes, offset, length);
        offset += length;
        if (offset == bytes.length)
            setConsumed(true);
        return length;
    }

    public void setString(String string)
    {
        this.bytes = string.getBytes(Charset.forName("UTF-8"));
        this.offset = 0;
    }
}
