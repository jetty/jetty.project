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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>A container for DATA frames metadata and content bytes.</p>
 * <p>Specialized subclasses (like {@link StringDataInfo}) may be used by applications
 * to send specific types of content.</p>
 * <p>Applications may send multiple instances of {@link DataInfo}, usually of the same
 * type, via {@link Stream#data(DataInfo)}. The last instance must have the
 * {@link #isClose() close flag} set, so that the client knows that no more content is
 * expected.</p>
 */
public abstract class DataInfo
{
    /**
     * <p>Flag that indicates that this {@link DataInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public final static byte FLAG_CLOSE = 1;
    /**
     * <p>Flag that indicates that this {@link DataInfo}'s data is compressed.</p>
     *
     * @see #isCompress()
     * @see #getFlags()
     */
    public final static byte FLAG_COMPRESS = 2;

    private final AtomicInteger consumed = new AtomicInteger();
    private boolean close;
    private boolean compress;

    /**
     * <p>Creates a new {@link DataInfo} with the given close flag and no compression flag.</p>
     *
     * @param close the value of the close flag
     */
    public DataInfo(boolean close)
    {
        setClose(close);
    }

    /**
     * <p>Creates a new {@link DataInfo} with the given close flag and given compression flag.</p>
     *
     * @param close    the close flag
     * @param compress the compress flag
     */
    public DataInfo(boolean close, boolean compress)
    {
        setClose(close);
        setCompress(compress);
    }

    /**
     * @return the value of the compress flag
     * @see #setCompress(boolean)
     */
    public boolean isCompress()
    {
        return compress;
    }

    /**
     * @param compress the value of the compress flag
     * @see #isCompress()
     */
    public void setCompress(boolean compress)
    {
        this.compress = compress;
    }

    /**
     * @return the value of the close flag
     * @see #setClose(boolean)
     */
    public boolean isClose()
    {
        return close;
    }

    /**
     * @param close the value of the close flag
     * @see #isClose()
     */
    public void setClose(boolean close)
    {
        this.close = close;
    }

    /**
     * @return the close and compress flags as integer
     * @see #FLAG_CLOSE
     * @see #FLAG_COMPRESS
     */
    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_CLOSE : 0;
        flags |= isCompress() ? FLAG_COMPRESS : 0;
        return flags;
    }

    /**
     * @return the total number of content bytes
     * @see #available()
     */
    public abstract int length();

    /**
     * <p>Returns the available content bytes that can be read via {@link #readInto(ByteBuffer)}.</p>
     * <p>Each invocation to {@link #readInto(ByteBuffer)} modifies the value returned by this method,
     * until no more content bytes are available.</p>
     *
     * @return the available content bytes
     * @see #readInto(ByteBuffer)
     */
    public abstract int available();

    /**
     * <p>Copies the content bytes of this {@link DataInfo} into the given {@link ByteBuffer}.</p>
     * <p>If the given {@link ByteBuffer} cannot contain the whole content of this {@link DataInfo}
     * then {@link #available()} will return a positive value, and further content
     * may be retrieved by invoking again this method.</p>
     *
     * @param output the {@link ByteBuffer} to copy to bytes into
     * @return the number of bytes copied
     * @see #available()
     */
    public abstract int readInto(ByteBuffer output);

    public int drainInto(ByteBuffer output)
    {
        int read = readInto(output);
        consume(read);
        return read;
    }

    public void consume(int delta)
    {
        int read = length() - available();
        int newConsumed = consumed() + delta;
        if (newConsumed > read)
            throw new IllegalStateException("Consuming without reading: consumed " + newConsumed + " but only read " + read);
        consumed.addAndGet(delta);
    }

    public int consumed()
    {
        return consumed.get();
    }

    /**
     * @param charset the charset used to convert the bytes
     * @return a String with the content of this {@link DataInfo}
     */
    public String asString(String charset)
    {
        ByteBuffer buffer = ByteBuffer.allocate(available());
        readInto(buffer);
        buffer.flip();
        return Charset.forName(charset).decode(buffer).toString();
    }

    /**
     * @return a byte array with the content of this {@link DataInfo}
     */
    public byte[] asBytes()
    {
        ByteBuffer buffer = ByteBuffer.allocate(available());
        readInto(buffer);
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /**
     * @return a {@link ByteBuffer} with the content of this {@link DataInfo}
     */
    public ByteBuffer asByteBuffer()
    {
        ByteBuffer buffer = ByteBuffer.allocate(available());
        readInto(buffer);
        buffer.flip();
        return buffer;
    }

    @Override
    public String toString()
    {
        return String.format("DATA @%x available=%d consumed=%d close=%b compress=%b", hashCode(), available(), consumed(), isClose(), isCompress());
    }
}
