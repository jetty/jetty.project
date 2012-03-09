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
 * <p>Receivers of {@link DataInfo} via {@link StreamFrameListener#onData(Stream, DataInfo)}
 * have two different APIs to read the data content bytes: a {@link #readInto(ByteBuffer) read}
 * API that does not interact with flow control, and a {@link #consumeInto(ByteBuffer) drain}
 * API that interacts with flow control.</p>
 * <p>Flow control is defined so that when the sender wants to sends a number of bytes larger
 * than the {@link Settings.ID#INITIAL_WINDOW_SIZE} value, it will stop sending as soon as it
 * has sent a number of bytes equal to the window size. The receiver has to <em>consume</em>
 * the data bytes that it received in order to tell the sender to send more bytes.</p>
 * <p>Consuming the data bytes can be done only via {@link #consumeInto(ByteBuffer)} or by a combination
 * of {@link #readInto(ByteBuffer)} and {@link #consume(int)} (possibly at different times).</p>
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
     * then after the read {@link #available()} will return a positive value, and further content
     * may be retrieved by invoking again this method with a new output buffer.</p>
     *
     * @param output the {@link ByteBuffer} to copy to bytes into
     * @return the number of bytes copied
     * @see #available()
     * @see #consumeInto(ByteBuffer)
     */
    public abstract int readInto(ByteBuffer output);

    /**
     * <p>Reads and consumes the content bytes of this {@link DataInfo} into the given {@link ByteBuffer}.</p>
     *
     * @param output the {@link ByteBuffer} to copy to bytes into
     * @return the number of bytes copied
     * @see #consume(int)
     */
    public int consumeInto(ByteBuffer output)
    {
        int read = readInto(output);
        consume(read);
        return read;
    }

    /**
     * <p>Consumes the given number of bytes from this {@link DataInfo}.</p>
     *
     * @param delta the number of bytes consumed
     */
    public void consume(int delta)
    {
        if (delta < 0)
            throw new IllegalArgumentException();
        int read = length() - available();
        int newConsumed = consumed() + delta;
//        if (newConsumed > read)
//            throw new IllegalStateException("Consuming without reading: consumed " + newConsumed + " but only read " + read);
        consumed.addAndGet(delta);
    }

    /**
     * @return the number of bytes consumed
     */
    public int consumed()
    {
        return consumed.get();
    }

    /**
     *
     * @param charset the charset used to convert the bytes
     * @param consume whether to consume the content
     * @return a String with the content of this {@link DataInfo}
     */
    public String asString(String charset, boolean consume)
    {
        ByteBuffer buffer = asByteBuffer(consume);
        return Charset.forName(charset).decode(buffer).toString();
    }

    /**
     * @return a byte array with the content of this {@link DataInfo}
     * @param consume whether to consume the content
     */
    public byte[] asBytes(boolean consume)
    {
        ByteBuffer buffer = asByteBuffer(consume);
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /**
     * @return a {@link ByteBuffer} with the content of this {@link DataInfo}
     * @param consume whether to consume the content
     */
    public ByteBuffer asByteBuffer(boolean consume)
    {
        ByteBuffer buffer = allocate(available());
        if (consume)
            consumeInto(buffer);
        else
            readInto(buffer);
        buffer.flip();
        return buffer;
    }

    protected ByteBuffer allocate(int size)
    {
        return ByteBuffer.allocate(size);
    }

    @Override
    public String toString()
    {
        return String.format("DATA @%x available=%d consumed=%d close=%b compress=%b", hashCode(), available(), consumed(), isClose(), isCompress());
    }
}
