//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.util.BufferUtil;

/**
 * {@link ContentDecoder} for the "gzip" encoding.
 */
public class GZIPContentDecoder implements ContentDecoder
{
    private final Inflater inflater = new Inflater(true);
    private final byte[] bytes;
    private byte[] output;
    private State state;
    private int size;
    private int value;
    private byte flags;

    public GZIPContentDecoder()
    {
        this(2048);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this.bytes = new byte[bufferSize];
        reset();
    }

    /**
     * {@inheritDoc}
     * <p>If the decoding did not produce any output, for example because it consumed gzip header
     * or trailer bytes, it returns a buffer with zero capacity.</p>
     * <p>This method never returns null.</p>
     * <p>The given {@code buffer}'s position will be modified to reflect the bytes consumed during
     * the decoding.</p>
     * <p>The decoding may be finished without consuming the buffer completely if the buffer contains
     * gzip bytes plus other bytes (either plain or gzipped).</p>
     */
    @Override
    public ByteBuffer decode(ByteBuffer buffer)
    {
        try
        {
            while (buffer.hasRemaining())
            {
                byte currByte = buffer.get();
                switch (state)
                {
                    case INITIAL:
                    {
                        buffer.position(buffer.position() - 1);
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
                        buffer.position(buffer.position() - 1);
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
                        buffer.position(buffer.position() - 1);
                        while (true)
                        {
                            int decoded = inflate(bytes);
                            if (decoded == 0)
                            {
                                if (inflater.needsInput())
                                {
                                    if (buffer.hasRemaining())
                                    {
                                        byte[] input = new byte[buffer.remaining()];
                                        buffer.get(input);
                                        inflater.setInput(input);
                                    }
                                    else
                                    {
                                        if (output != null)
                                        {
                                            ByteBuffer result = ByteBuffer.wrap(output);
                                            output = null;
                                            return result;
                                        }
                                        break;
                                    }
                                }
                                else if (inflater.finished())
                                {
                                    int remaining = inflater.getRemaining();
                                    buffer.position(buffer.limit() - remaining);
                                    state = State.CRC;
                                    size = 0;
                                    value = 0;
                                    break;
                                }
                                else
                                {
                                    throw new ZipException("Invalid inflater state");
                                }
                            }
                            else
                            {
                                if (output == null)
                                {
                                    // Save the inflated bytes and loop to see if we have finished
                                    output = Arrays.copyOf(bytes, decoded);
                                }
                                else
                                {
                                    // Accumulate inflated bytes and loop to see if we have finished
                                    byte[] newOutput = Arrays.copyOf(output, output.length+decoded);
                                    System.arraycopy(bytes, 0, newOutput, output.length, decoded);
                                    output = newOutput;
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

                            ByteBuffer result = output == null ? BufferUtil.EMPTY_BUFFER : ByteBuffer.wrap(output);
                            reset();
                            return result;
                        }
                        break;
                    }
                    default:
                        throw new ZipException();
                }
            }
            return BufferUtil.EMPTY_BUFFER;
        }
        catch (ZipException x)
        {
            throw new RuntimeException(x);
        }
    }

    private int inflate(byte[] bytes) throws ZipException
    {
        try
        {
            return inflater.inflate(bytes);
        }
        catch (DataFormatException x)
        {
            throw new ZipException(x.getMessage());
        }
    }

    private void reset()
    {
        inflater.reset();
        Arrays.fill(bytes, (byte)0);
        output = null;
        state = State.INITIAL;
        size = 0;
        value = 0;
        flags = 0;
    }

    protected boolean isFinished()
    {
        return state == State.INITIAL;
    }

    /**
     * Specialized {@link ContentDecoder.Factory} for the "gzip" encoding.
     */
    public static class Factory extends ContentDecoder.Factory
    {
        private final int bufferSize;

        public Factory()
        {
            this(2048);
        }

        public Factory(int bufferSize)
        {
            super("gzip");
            this.bufferSize = bufferSize;
        }

        @Override
        public ContentDecoder newContentDecoder()
        {
            return new GZIPContentDecoder(bufferSize);
        }
    }

    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE
    }
}
