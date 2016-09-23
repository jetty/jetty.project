//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

/**
 * Decoder for the "gzip" encoding.
 * 
 * @TODO this is a work in progress
 */
public class GZIPContentDecoder
{
    private final Inflater inflater = new Inflater(true);
    private final ByteBufferPool pool;
    private final int bufferSize;
    private State state;
    private int size;
    private int value;
    private byte flags;
    private ByteBuffer inflated;

    public GZIPContentDecoder()
    {
        this(null,2048);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this(null,bufferSize);
    }
    
    public GZIPContentDecoder(ByteBufferPool pool, int bufferSize)
    {
        this.bufferSize = bufferSize;
        this.pool = pool;
        reset();
    }

    /**
     * <p>If the decoding did not produce any output, for example because it consumed gzip header
     * or trailer bytes, it returns a buffer with zero capacity.</p>
     * <p>This method never returns null.</p>
     * <p>The given {@code buffer}'s position will be modified to reflect the bytes consumed during
     * the decoding.</p>
     * <p>The decoding may be finished without consuming the buffer completely if the buffer contains
     * gzip bytes plus other bytes (either plain or gzipped).</p>
     */
    public ByteBuffer decode(ByteBuffer compressed)
    {
        decodeChunks(compressed);
        return inflated==null?BufferUtil.EMPTY_BUFFER:inflated;
    }

    protected void decodedChunk(ByteBuffer chunk)
    {
        if (inflated==null)
            inflated=chunk;
        else
        {
            int size = inflated.remaining() + chunk.remaining();
            if (size<=inflated.capacity())
            {
                BufferUtil.put(chunk,inflated);
                release(chunk);
            }
            else
            {
                ByteBuffer bigger=pool==null?BufferUtil.allocate(size):pool.acquire(size,false);
                BufferUtil.put(inflated,bigger);
                release(inflated);
                BufferUtil.put(chunk,bigger);
                release(chunk);
            }
        }
    }
    
    
    /**
     * <p>If the decoding did not produce any output, for example because it consumed gzip header
     * or trailer bytes, it returns a buffer with zero capacity.</p>
     * <p>This method never returns null.</p>
     * <p>The given {@code buffer}'s position will be modified to reflect the bytes consumed during
     * the decoding.</p>
     * <p>The decoding may be finished without consuming the buffer completely if the buffer contains
     * gzip bytes plus other bytes (either plain or gzipped).</p>
     */
    protected void decodeChunks(ByteBuffer compressed)
    {
        try
        {
            while (compressed.hasRemaining())
            {
                byte currByte = compressed.get();
                switch (state)
                {
                    case INITIAL:
                    {
                        compressed.position(compressed.position() - 1);
                        state = State.ID;
                        break;
                    }
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
                    case FLAGS:
                    {
                        compressed.position(compressed.position() - 1);
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
                            state = State.DATA;
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
                    case DATA:
                    {
                        // TODO this will not always be possible
                        compressed.position(compressed.position() - 1);
                        ByteBuffer inflated=null;
                        while (true)
                        {
                            ByteBuffer chunk = inflate();
                            if (chunk.hasRemaining())
                                decodedChunk(chunk);
                            else if (inflater.needsInput())
                            {
                                if (compressed.hasRemaining())
                                {
                                    // TODO do this without copy from buffer array
                                    byte[] input = new byte[compressed.remaining()];
                                    compressed.get(input);
                                    inflater.setInput(input);
                                }
                                else if (inflater.finished())
                                {
                                    int remaining = inflater.getRemaining();
                                    compressed.position(compressed.limit() - remaining);
                                    state = State.CRC;
                                    size = 0;
                                    value = 0;
                                    break;
                                }
                                else
                                {
                                    break;
                                }
                            }
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
                        value += (currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 4)
                        {
                            if (value != inflater.getBytesWritten())
                                throw new ZipException("Invalid input size");

                            // TODO ByteBuffer result = output == null ? BufferUtil.EMPTY_BUFFER : ByteBuffer.wrap(output);
                            reset();
                            return ;
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
    }

    private ByteBuffer inflate() throws ZipException
    {
        try
        {
            ByteBuffer inflated = acquire();
            int length = inflater.inflate(inflated.array(),inflated.arrayOffset(),inflated.capacity());
            inflated.limit(length);
            return inflated;
        }
        catch (DataFormatException x)
        {
            throw new ZipException(x.getMessage());
        }
    }

    private void reset()
    {
        inflater.reset();
        state = State.INITIAL;
        size = 0;
        value = 0;
        flags = 0;
    }

    public boolean isFinished()
    {
        return state == State.INITIAL;
    }

    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE
    }
    
    public ByteBuffer acquire()
    {
        return pool==null?BufferUtil.allocate(bufferSize):pool.acquire(bufferSize,false);
    }
    
    public void release(ByteBuffer buffer)
    {
        if (pool!=null)
            pool.release(buffer);
    }
}
