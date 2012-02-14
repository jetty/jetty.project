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

public abstract class DataInfo
{
    public final static byte FLAG_FIN = 1;
    public final static byte FLAG_COMPRESS = 2;

    private boolean close;
    private boolean compress;
    private boolean consumed;

    public DataInfo(boolean close)
    {
        setClose(close);
    }

    public DataInfo(boolean close, boolean compress)
    {
        setClose(close);
        setCompress(compress);
    }

    public boolean isCompress()
    {
        return compress;
    }

    public void setCompress(boolean compress)
    {
        this.compress = compress;
    }

    public boolean isClose()
    {
        return close;
    }

    public void setClose(boolean close)
    {
        this.close = close;
    }

    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_FIN : 0;
        flags |= isCompress() ? FLAG_COMPRESS : 0;
        return flags;
    }

    public abstract int getBytesCount();

    public abstract int getBytes(ByteBuffer output);

    public String asString(String charset)
    {
        ByteBuffer buffer = ByteBuffer.allocate(getBytesCount());
        getBytes(buffer);
        buffer.flip();
        return Charset.forName(charset).decode(buffer).toString();
    }

    public byte[] asBytes()
    {
        ByteBuffer buffer = ByteBuffer.allocate(getBytesCount());
        getBytes(buffer);
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    public boolean isConsumed()
    {
        return consumed;
    }

    protected void setConsumed(boolean consumed)
    {
        this.consumed = consumed;
    }

    @Override
    public String toString()
    {
        return String.format("DATA @%x length=%d close=%b compress=%b", hashCode(), getBytesCount(), isClose(), isCompress());
    }
}
