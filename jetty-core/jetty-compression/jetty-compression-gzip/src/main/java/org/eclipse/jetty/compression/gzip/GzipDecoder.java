//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.compression.gzip;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.compression.InflaterPool;

class GzipDecoder implements Compression.Decoder, Destroyable
{
    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE
    }

    // Unsigned Integer Max == 2^32
    private static final long UINT_MAX = 0xFFFFFFFFL;

    private final List<RetainableByteBuffer> inflateds = new ArrayList<>();
    private final ByteBufferPool pool;
    private final int bufferSize;
    private InflaterPool.Entry inflaterEntry;
    private Inflater inflater;
    private State state;
    private int size;
    private long value;
    private byte flags;
    private RetainableByteBuffer inflated;

    public GzipDecoder(GzipCompression gzipCompression, ByteBufferPool pool)
    {
        this.pool = Objects.requireNonNull(pool);
        this.bufferSize = gzipCompression.getBufferSize();
        this.inflaterEntry = gzipCompression.getInflaterPool().acquire();
        this.inflater = inflaterEntry.get();
        reset();
    }

    @Override
    public void close()
    {
        // do nothing
    }

    /**
     * <p>Inflates compressed data from a buffer.</p>
     * <p>The {@link RetainableByteBuffer} returned by this method
     * <b>must</b> be released via {@link RetainableByteBuffer#release()}.</p>
     * <p>This method may fully consume the input buffer, but return
     * only a chunk of the inflated bytes, to allow applications to
     * consume the inflated chunk before performing further inflation,
     * applying backpressure. In this case, this method should be
     * invoked again with the same input buffer (even if
     * it's already fully consumed) and that will produce another
     * chunk of inflated bytes. Termination happens when the input
     * buffer is fully consumed, and the returned buffer is empty.</p>
     * <p>See {@link #decodedChunk(RetainableByteBuffer)} to perform inflating
     * in a non-blocking way that allows to apply backpressure.</p>
     *
     * @param compressed the buffer containing compressed data.
     * @return a buffer containing inflated data.
     */
    public RetainableByteBuffer decode(ByteBuffer compressed)
    {
        decodeChunks(compressed);

        if (inflateds.isEmpty())
        {
            if ((inflated == null || !inflated.hasRemaining()) || state == State.CRC || state == State.ISIZE)
                return acquire(0);
            RetainableByteBuffer result = inflated;
            inflated = null;
            return result;
        }
        else
        {
            inflateds.add(inflated);
            inflated = null;
            int length = inflateds.stream().mapToInt(RetainableByteBuffer::remaining).sum();
            RetainableByteBuffer result = acquire(length);
            for (RetainableByteBuffer buffer : inflateds)
            {
                buffer.appendTo(result);
                buffer.release();
            }
            inflateds.clear();
            return result;
        }
    }

    @Override
    public void destroy()
    {
        inflaterEntry.release();
        inflaterEntry = null;
        inflater = null;
    }

    public boolean isFinished()
    {
        return state == State.INITIAL;
    }

    /**
     * <p>Inflates compressed data.</p>
     * <p>Inflation continues until the compressed block end is reached, there is no
     * more compressed data or a call to {@link #decodedChunk(RetainableByteBuffer)} returns true.</p>
     *
     * @param compressed the buffer of compressed data to inflate
     */
    protected void decodeChunks(ByteBuffer compressed)
    {
        RetainableByteBuffer buffer = null;
        try
        {
            while (true)
            {
                switch (state)
                {
                    case INITIAL:
                    {
                        state = State.ID;
                        break;
                    }

                    case FLAGS:
                    {
                        if ((flags & 0x04) == 0x04)
                        {
                            state = State.EXTRA_LENGTH;
                            size = 0;
                            value = 0;
                        }
                        else if ((flags & 0x08) == 0x08)
                            state = State.NAME;
                        else if ((flags & 0x10) == 0x10)
                            state = State.COMMENT;
                        else if ((flags & 0x2) == 0x2)
                        {
                            state = State.HCRC;
                            size = 0;
                            value = 0;
                        }
                        else
                        {
                            state = State.DATA;
                            continue;
                        }
                        break;
                    }

                    case DATA:
                    {
                        while (true)
                        {
                            if (buffer == null)
                                buffer = acquire(bufferSize);

                            try
                            {
                                ByteBuffer decoded = buffer.getByteBuffer();
                                int pos = BufferUtil.flipToFill(decoded);
                                inflater.inflate(decoded);
                                BufferUtil.flipToFlush(decoded, pos);
                            }
                            catch (DataFormatException x)
                            {
                                throw new ZipException(x.getMessage());
                            }

                            if (buffer.hasRemaining())
                            {
                                boolean stop = decodedChunk(buffer);
                                buffer.release();
                                buffer = null;
                                if (stop)
                                    return;
                            }
                            else if (inflater.needsInput())
                            {
                                if (!compressed.hasRemaining())
                                    return;
                                inflater.setInput(compressed);
                            }
                            else if (inflater.finished())
                            {
                                state = State.CRC;
                                size = 0;
                                value = 0;
                                break;
                            }
                        }
                        continue;
                    }

                    default:
                        break;
                }

                if (!compressed.hasRemaining())
                    break;

                byte currByte = compressed.get();
                switch (state)
                {
                    case ID:
                    {
                        value += (currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 2)
                        {
                            if (value != 0x8B1F)
                                throw new ZipException("Invalid gzip bytes");
                            state = State.CM;
                        }
                        break;
                    }
                    case CM:
                    {
                        if ((currByte & 0xFF) != 0x08)
                            throw new ZipException("Invalid gzip compression method");
                        state = State.FLG;
                        break;
                    }
                    case FLG:
                    {
                        flags = currByte;
                        state = State.MTIME;
                        size = 0;
                        value = 0;
                        break;
                    }
                    case MTIME:
                    {
                        // Skip the 4 MTIME bytes
                        ++size;
                        if (size == 4)
                            state = State.XFL;
                        break;
                    }
                    case XFL:
                    {
                        // Skip XFL
                        state = State.OS;
                        break;
                    }
                    case OS:
                    {
                        // Skip OS
                        state = State.FLAGS;
                        break;
                    }
                    case EXTRA_LENGTH:
                    {
                        value += (currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 2)
                            state = State.EXTRA;
                        break;
                    }
                    case EXTRA:
                    {
                        // Skip EXTRA bytes
                        --value;
                        if (value == 0)
                        {
                            // Clear the EXTRA flag and loop on the flags
                            flags &= ~0x04;
                            state = State.FLAGS;
                        }
                        break;
                    }
                    case NAME:
                    {
                        // Skip NAME bytes
                        if (currByte == 0)
                        {
                            // Clear the NAME flag and loop on the flags
                            flags &= ~0x08;
                            state = State.FLAGS;
                        }
                        break;
                    }
                    case COMMENT:
                    {
                        // Skip COMMENT bytes
                        if (currByte == 0)
                        {
                            // Clear the COMMENT flag and loop on the flags
                            flags &= ~0x10;
                            state = State.FLAGS;
                        }
                        break;
                    }
                    case HCRC:
                    {
                        // Skip HCRC
                        ++size;
                        if (size == 2)
                        {
                            // Clear the HCRC flag and loop on the flags
                            flags &= ~0x02;
                            state = State.FLAGS;
                        }
                        break;
                    }
                    case CRC:
                    {
                        value += (currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 4)
                        {
                            // From RFC 1952, compliant decoders need not to verify the CRC
                            state = State.ISIZE;
                            size = 0;
                            value = 0;
                        }
                        break;
                    }
                    case ISIZE:
                    {
                        value = value | ((currByte & 0xFFL) << (8 * size));
                        ++size;
                        if (size == 4)
                        {
                            // RFC 1952: Section 2.3.1; ISIZE is the input size modulo 2^32
                            if (value != (inflater.getBytesWritten() & UINT_MAX))
                                throw new ZipException("Invalid input size");

                            reset();
                            return;
                        }
                        break;
                    }
                    default:
                        throw new ZipException();
                }
            }
        }
        catch (ZipException x)
        {
            throw new RuntimeException(x);
        }
        finally
        {
            if (buffer != null)
                buffer.release();
        }
    }

    /**
     * <p>Called when a chunk of data is inflated.</p>
     * <p>The default implementation aggregates all the chunks
     * into a single buffer returned from {@link #decode(ByteBuffer)}.</p>
     * <p>Derived implementations may choose to consume inflated chunks
     * individually and return {@code true} from this method to prevent
     * further inflation until a subsequent call to {@link #decode(ByteBuffer)}
     * or {@link #decodeChunks(ByteBuffer)} is made.
     *
     * @param chunk the inflated chunk of data
     * @return false if inflating should continue, or true if the call
     * to {@link #decodeChunks(ByteBuffer)} or {@link #decode(ByteBuffer)}
     * should return, allowing to consume the inflated chunk and apply
     * backpressure
     */
    protected boolean decodedChunk(RetainableByteBuffer chunk)
    {
        // Retain the chunk because it is stored for later use.
        chunk.retain();
        if (inflated != null)
            inflateds.add(inflated);
        inflated = chunk;
        return false;
    }

    /**
     * @param capacity capacity of the ByteBuffer to acquire
     * @return a heap buffer of the configured capacity either from the pool or freshly allocated.
     */
    private RetainableByteBuffer acquire(int capacity)
    {
        // Zero-capacity buffers aren't released, they MUST NOT come from the pool.
        if (capacity == 0)
            return RetainableByteBuffer.EMPTY;
        return pool.acquire(capacity, false);
    }

    private void reset()
    {
        inflater.reset();
        state = State.INITIAL;
        size = 0;
        value = 0;
        flags = 0;
    }
}
