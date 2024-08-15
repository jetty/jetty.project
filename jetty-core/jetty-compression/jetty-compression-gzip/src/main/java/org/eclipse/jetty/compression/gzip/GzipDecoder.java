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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.compression.BufferQueue;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GzipDecoder implements Compression.Decoder, Destroyable
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipDecoder.class);

    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE, FINISHED, ERROR
    }

    // Unsigned Integer Max == 2^32
    private static final long UINT_MAX = 0xFFFFFFFFL;

    private final GzipCompression compression;
    private final BufferQueue outputBuffers;
    private InflaterPool.Entry inflaterEntry;
    private Inflater inflater;
    private State state;
    private int size;
    private long value;
    private byte flags;

    public GzipDecoder(GzipCompression gzipCompression)
    {
        this.compression = Objects.requireNonNull(gzipCompression);
        this.outputBuffers = new BufferQueue();
        this.inflaterEntry = gzipCompression.getInflaterPool().acquire();
        this.inflater = inflaterEntry.get();
        this.inflater.reset();
        this.state = State.INITIAL;
    }

    @Override
    public void close()
    {
        outputBuffers.close();
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
     *
     * @param compressed the buffer containing compressed data.
     * @return a buffer containing inflated data.
     * @throws IOException if unable to decode/parser chunks
     */
    public RetainableByteBuffer decode(ByteBuffer compressed) throws IOException
    {
        if (state == State.FINISHED && compressed.hasRemaining())
            throw new IllegalStateException("finishInput already called, cannot read input buffer");

        // can we progress any?
        if (compressed.hasRemaining())
            decodeChunks(compressed);

        RetainableByteBuffer uncompressed = outputBuffers.getRetainableBuffer();
        LOG.debug("decode({}) - uncompressed:{}", compressed, uncompressed);
        if (uncompressed == null)
            return compression.acquireByteBuffer(0);

        return uncompressed;
    }

    @Override
    public void finishInput() throws IOException
    {
        if (state == State.FINISHED)
            return;

        if (state != State.INITIAL)
        {
            StringBuilder msg = new StringBuilder();
            msg.append("Decoder failure [").append(state).append("] - needsInput:");
            msg.append(inflater.needsInput());
            state = State.FINISHED;
            throw new IOException(msg.toString());
        }
    }

    @Override
    public boolean isOutputComplete()
    {
        return !outputBuffers.hasRemaining();
    }

    @Override
    public void destroy()
    {
        close();
        inflater.reset();
        inflaterEntry.release();
        inflater = null;
        inflaterEntry = null;
    }

    /**
     * <p>Inflates compressed data.</p>
     *
     * @param compressed the buffer of compressed data to inflate
     * @throws IOException if unable to parse/decode chunks
     */
    protected void decodeChunks(ByteBuffer compressed) throws IOException
    {
        // parse
        try
        {
            while (compressed.hasRemaining())
            {
                switch (state)
                {
                    case INITIAL:
                    {
                        inflater.reset();
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
//                        while (true)
                        {
                            try
                            {
                                RetainableByteBuffer buffer = compression.acquireByteBuffer();
                                ByteBuffer decoded = buffer.getByteBuffer();
                                int pos = BufferUtil.flipToFill(decoded);
                                inflater.inflate(decoded);
                                BufferUtil.flipToFlush(decoded, pos);
                                if (buffer.hasRemaining())
                                {
                                    outputBuffers.addCopyOf(buffer);
                                }
                                else
                                {
                                    buffer.release();
                                }
                            }
                            catch (DataFormatException x)
                            {
                                throw new ZipException(x.getMessage());
                            }

                            if (inflater.needsInput())
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

                byte currByte = compressed.get();
                switch (state)
                {
                    case ERROR, FINISHED:
                    {
                        // skip rest of content (nothing else possible to read safely)
                        compressed.position(compressed.limit());
                        return;
                    }

                    case ID:
                    {
                        value += (currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 2)
                        {
                            if (value != 0x8B1F)
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Skipping rest of input, no gzip magic number detected");
                                state = State.INITIAL;
                                compressed.position(compressed.limit());
                                return;
                            }
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
                            state = State.INITIAL;
                            size = 0;
                            value = 0;
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
            state = State.ERROR;
            throw x;
        }
    }
}
