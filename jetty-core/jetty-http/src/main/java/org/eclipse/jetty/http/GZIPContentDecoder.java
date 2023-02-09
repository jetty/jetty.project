//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.compression.InflaterPool;

/**
 * <p>Decoder for the "gzip" content encoding.</p>
 * <p>This decoder inflates gzip compressed data, and has
 * been optimized for async usage with minimal data copies.</p>
 */
public class GZIPContentDecoder implements Destroyable
{
    // Unsigned Integer Max == 2^32
    private static final long UINT_MAX = 0xFFFFFFFFL;

    private final List<RetainableByteBuffer> _inflateds = new ArrayList<>();
    private final ByteBufferPool _pool;
    private final int _bufferSize;
    private InflaterPool.Entry _inflaterEntry;
    private Inflater _inflater;
    private State _state;
    private int _size;
    private long _value;
    private byte _flags;
    private RetainableByteBuffer _inflated;

    public GZIPContentDecoder()
    {
        this(null, 2048);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this(null, bufferSize);
    }

    public GZIPContentDecoder(ByteBufferPool byteBufferPool, int bufferSize)
    {
        this(new InflaterPool(0, true), byteBufferPool, bufferSize);
    }

    public GZIPContentDecoder(InflaterPool inflaterPool, ByteBufferPool byteBufferPool, int bufferSize)
    {
        _inflaterEntry = inflaterPool.acquire();
        _inflater = _inflaterEntry.get();
        _bufferSize = bufferSize;
        _pool = byteBufferPool != null ? byteBufferPool : new ByteBufferPool.NonPooling();
        reset();
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

        if (_inflateds.isEmpty())
        {
            if ((_inflated == null || !_inflated.hasRemaining()) || _state == State.CRC || _state == State.ISIZE)
                return acquire(0);
            RetainableByteBuffer result = _inflated;
            _inflated = null;
            return result;
        }
        else
        {
            _inflateds.add(_inflated);
            _inflated = null;
            int length = _inflateds.stream().mapToInt(RetainableByteBuffer::remaining).sum();
            RetainableByteBuffer result = acquire(length);
            for (RetainableByteBuffer buffer : _inflateds)
            {
                BufferUtil.append(result.getByteBuffer(), buffer.getByteBuffer());
                buffer.release();
            }
            _inflateds.clear();
            return result;
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
        if (_inflated != null)
            _inflateds.add(_inflated);
        _inflated = chunk;
        return false;
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
                switch (_state)
                {
                    case INITIAL:
                    {
                        _state = State.ID;
                        break;
                    }

                    case FLAGS:
                    {
                        if ((_flags & 0x04) == 0x04)
                        {
                            _state = State.EXTRA_LENGTH;
                            _size = 0;
                            _value = 0;
                        }
                        else if ((_flags & 0x08) == 0x08)
                            _state = State.NAME;
                        else if ((_flags & 0x10) == 0x10)
                            _state = State.COMMENT;
                        else if ((_flags & 0x2) == 0x2)
                        {
                            _state = State.HCRC;
                            _size = 0;
                            _value = 0;
                        }
                        else
                        {
                            _state = State.DATA;
                            continue;
                        }
                        break;
                    }

                    case DATA:
                    {
                        while (true)
                        {
                            if (buffer == null)
                                buffer = acquire(_bufferSize);

                            try
                            {
                                ByteBuffer decoded = buffer.getByteBuffer();
                                int pos = BufferUtil.flipToFill(decoded);
                                _inflater.inflate(decoded);
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
                            else if (_inflater.needsInput())
                            {
                                if (!compressed.hasRemaining())
                                    return;
                                _inflater.setInput(compressed);
                            }
                            else if (_inflater.finished())
                            {
                                _state = State.CRC;
                                _size = 0;
                                _value = 0;
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
                switch (_state)
                {
                    case ID:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 2)
                        {
                            if (_value != 0x8B1F)
                                throw new ZipException("Invalid gzip bytes");
                            _state = State.CM;
                        }
                        break;
                    }
                    case CM:
                    {
                        if ((currByte & 0xFF) != 0x08)
                            throw new ZipException("Invalid gzip compression method");
                        _state = State.FLG;
                        break;
                    }
                    case FLG:
                    {
                        _flags = currByte;
                        _state = State.MTIME;
                        _size = 0;
                        _value = 0;
                        break;
                    }
                    case MTIME:
                    {
                        // Skip the 4 MTIME bytes
                        ++_size;
                        if (_size == 4)
                            _state = State.XFL;
                        break;
                    }
                    case XFL:
                    {
                        // Skip XFL
                        _state = State.OS;
                        break;
                    }
                    case OS:
                    {
                        // Skip OS
                        _state = State.FLAGS;
                        break;
                    }
                    case EXTRA_LENGTH:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 2)
                            _state = State.EXTRA;
                        break;
                    }
                    case EXTRA:
                    {
                        // Skip EXTRA bytes
                        --_value;
                        if (_value == 0)
                        {
                            // Clear the EXTRA flag and loop on the flags
                            _flags &= ~0x04;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case NAME:
                    {
                        // Skip NAME bytes
                        if (currByte == 0)
                        {
                            // Clear the NAME flag and loop on the flags
                            _flags &= ~0x08;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case COMMENT:
                    {
                        // Skip COMMENT bytes
                        if (currByte == 0)
                        {
                            // Clear the COMMENT flag and loop on the flags
                            _flags &= ~0x10;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case HCRC:
                    {
                        // Skip HCRC
                        ++_size;
                        if (_size == 2)
                        {
                            // Clear the HCRC flag and loop on the flags
                            _flags &= ~0x02;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case CRC:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 4)
                        {
                            // From RFC 1952, compliant decoders need not to verify the CRC
                            _state = State.ISIZE;
                            _size = 0;
                            _value = 0;
                        }
                        break;
                    }
                    case ISIZE:
                    {
                        _value = _value | ((currByte & 0xFFL) << (8 * _size));
                        ++_size;
                        if (_size == 4)
                        {
                            // RFC 1952: Section 2.3.1; ISIZE is the input size modulo 2^32
                            if (_value != (_inflater.getBytesWritten() & UINT_MAX))
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

    private void reset()
    {
        _inflater.reset();
        _state = State.INITIAL;
        _size = 0;
        _value = 0;
        _flags = 0;
    }

    @Override
    public void destroy()
    {
        _inflaterEntry.release();
        _inflaterEntry = null;
        _inflater = null;
    }

    public boolean isFinished()
    {
        return _state == State.INITIAL;
    }

    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE
    }

    /**
     * @param capacity capacity of the ByteBuffer to acquire
     * @return a heap buffer of the configured capacity either from the pool or freshly allocated.
     */
    public RetainableByteBuffer acquire(int capacity)
    {
        return _pool.acquire(capacity, false);
    }
}
