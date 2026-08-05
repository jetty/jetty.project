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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsing of a frames in WebSocket land.
 */
public class Parser
{
    private enum State
    {
        START,
        PAYLOAD_LEN,
        PAYLOAD_LEN_BYTES,
        MASK,
        MASK_BYTES,
        PAYLOAD,
        FRAGMENT
    }

    private static final Logger LOG = LoggerFactory.getLogger(Parser.class);

    private final WritableBufferPool bufferPool;
    private final Configuration configuration;

    // State specific
    private State state = State.START;
    private byte firstByte;
    private int cursor;
    private byte[] mask;
    private int payloadLength;
    private RetainableByteBuffer.Mutable aggregate;

    public Parser(WritableBufferPool bufferPool)
    {
        this(bufferPool, new Configuration.ConfigurationCustomizer());
    }

    public Parser(WritableBufferPool bufferPool, Configuration configuration)
    {
        this.bufferPool = bufferPool;
        this.configuration = configuration;
    }

    public long getPayloadLength()
    {
        return payloadLength;
    }

    public void reset()
    {
        state = State.START;
        firstByte = 0;
        mask = null;
        cursor = 0;
        aggregate = null;
        payloadLength = 0;
    }

    /**
     * Parse the buffer.
     *
     * @param buffer the buffer to parse from.
     * @return Frame or null if not enough data for a complete frame.
     * @throws WebSocketException if unable to parse properly
     */
    public Frame.Parsed parse(org.eclipse.jetty.util.buffer.RetainableByteBuffer buffer) throws WebSocketException
    {
        try
        {
            // parse through
            while (buffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} Parsing {}", this, buffer);

                switch (state)
                {
                    case START:
                    {
                        // peek at byte
                        firstByte = buffer.get();
                        state = State.PAYLOAD_LEN;
                        checkFirstByte(firstByte);
                        break;
                    }

                    case PAYLOAD_LEN:
                    {
                        byte b = buffer.get();

                        if ((b & 0x80) != 0)
                            mask = new byte[4];

                        payloadLength = (byte)(0x7F & b);

                        if (payloadLength == 127) // 0x7F
                        {
                            // length 8 bytes (extended payload length)
                            payloadLength = 0;
                            state = State.PAYLOAD_LEN_BYTES;
                            cursor = 8;
                        }
                        else if (payloadLength == 126) // 0x7E
                        {
                            // length 2 bytes (extended payload length)
                            payloadLength = 0;
                            state = State.PAYLOAD_LEN_BYTES;
                            cursor = 2;
                        }
                        else if (mask != null)
                        {
                            state = State.MASK;
                        }
                        else if (payloadLength == 0)
                        {
                            state = State.START;
                            return newFrame(firstByte, mask, null, null);
                        }
                        else
                        {
                            state = State.PAYLOAD;
                        }
                        break;
                    }

                    case PAYLOAD_LEN_BYTES:
                    {
                        byte b = buffer.get();
                        --cursor;
                        long longLengthAccumulator = payloadLength;
                        longLengthAccumulator |= (long)(b & 0xFF) << (8 * cursor);
                        if (longLengthAccumulator > Integer.MAX_VALUE || longLengthAccumulator < 0)
                            throw new MessageTooLargeException("Frame payload exceeded integer max value");
                        payloadLength = Math.toIntExact(longLengthAccumulator);
                        if (cursor == 0)
                        {
                            if (mask != null)
                            {
                                state = State.MASK;
                            }
                            else if (payloadLength == 0)
                            {
                                state = State.START;
                                return newFrame(firstByte, mask, null, null);
                            }
                            else
                            {
                                state = State.PAYLOAD;
                            }
                        }
                        break;
                    }

                    case MASK:
                    {
                        if (buffer.remaining() >= 4)
                        {
                            buffer.get(mask, 0, 4);
                            if (payloadLength == 0)
                            {
                                state = State.START;
                                return newFrame(firstByte, mask, null, null);
                            }
                            state = State.PAYLOAD;
                        }
                        else
                        {
                            state = State.MASK_BYTES;
                            cursor = 4;
                        }
                        break;
                    }

                    case MASK_BYTES:
                    {
                        byte b = buffer.get();
                        mask[4 - cursor] = b;
                        --cursor;
                        if (cursor == 0)
                        {
                            if (payloadLength == 0)
                            {
                                state = State.START;
                                return newFrame(firstByte, mask, null, null);
                            }
                            state = State.PAYLOAD;
                        }
                        break;
                    }

                    case PAYLOAD:
                    case FRAGMENT:
                    {
                        if (aggregate == null)
                            checkFrameSize(OpCode.getOpCode(firstByte), payloadLength);
                        Frame.Parsed frame = parsePayload(buffer);
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} parsed {}", this, frame);
                        return frame;
                    }

                    default:
                        throw new IllegalStateException();
                }
            }
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} Parse Error {}", this, buffer, t);

            buffer.consume(buffer.remaining());

            // let session know
            WebSocketException wse;
            if (t instanceof WebSocketException)
                wse = (WebSocketException)t;
            else
                wse = new WebSocketException(t);

            throw wse;
        }
        finally
        {
            if (state == State.START)
                reset();
            if (LOG.isDebugEnabled())
                LOG.debug("{} Parse exit", this);
        }

        return null;
    }

    protected void checkFirstByte(byte firstByte)
    {
        // Validate OpCode
        byte opcode = OpCode.getOpCode(firstByte);
        if (!OpCode.isKnown(opcode))
            throw new ProtocolException("Unknown opcode: " + opcode);

        // Validate Control Frame
        boolean fin = ((firstByte & 0x80) != 0);
        if (OpCode.isControlFrame(opcode) && !fin)
            throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(opcode) + "]");
    }

    protected void checkFrameSize(byte opcode, int payloadLength) throws MessageTooLargeException, ProtocolException
    {
        if (payloadLength < 0)
            throw new IllegalArgumentException("Invalid payloadLength");

        if (OpCode.isControlFrame(opcode))
        {
            if (payloadLength > Frame.MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");
        }
        else if (OpCode.isDataFrame(opcode))
        {
            long maxFrameSize = configuration.getMaxFrameSize();
            if (!configuration.isAutoFragment() && maxFrameSize > 0 && payloadLength > maxFrameSize)
                throw new MessageTooLargeException("Cannot handle payload lengths larger than " + maxFrameSize);
        }
        else
        {
            throw new ProtocolException("Unknown opcode: " + opcode);
        }
    }

    protected Frame.Parsed newFrame(byte firstByte, byte[] mask, RetainableByteBuffer payload, Runnable releaser)
    {
        if (payload == null)
            return new Frame.Parsed(firstByte, mask, null, releaser);

        Frame.Parsed[] result = new Frame.Parsed[1];
        payload.quietWriteTo(b ->
        {
            result[0] = new Frame.Parsed(firstByte, mask, b, releaser);
            return b.remaining();
        });
        return result[0];
    }

    private Frame.Parsed autoFragment(RetainableByteBuffer buffer, int fragmentSize)
    {
        payloadLength -= fragmentSize;

        byte[] nextMask = null;
        if (mask != null)
        {
            int shift = fragmentSize % 4;
            nextMask = new byte[4];
            nextMask[0] = mask[(shift) % 4];
            nextMask[1] = mask[(1 + shift) % 4];
            nextMask[2] = mask[(2 + shift) % 4];
            nextMask[3] = mask[(3 + shift) % 4];
        }

        RetainableByteBuffer content = buffer.sliceAndConsume(fragmentSize);
        Frame.Parsed frame = newFrame((byte)(firstByte & 0x7F), mask, content, content::release);

        mask = nextMask;
        firstByte = (byte)((firstByte & 0x80) | OpCode.CONTINUATION);
        state = State.FRAGMENT;
        return frame;
    }

    private Frame.Parsed parsePayload(RetainableByteBuffer buffer)
    {
        if (payloadLength == 0)
            return null;

        if (!buffer.hasRemaining())
            return null;

        int available = (int)buffer.remaining();
        boolean isDataFrame = OpCode.isDataFrame(OpCode.getOpCode(firstByte));

        // Always autoFragment data frames if payloadLength is greater than maxFrameSize.
        // We have already checked payload size in checkFrameSize, so we know we can autoFragment if larger than maxFrameSize.
        long maxFrameSize = configuration.getMaxFrameSize();
        if (maxFrameSize > 0 && isDataFrame && payloadLength > maxFrameSize)
            return autoFragment(buffer, (int)Math.min(available, maxFrameSize));

        if (aggregate == null)
        {
            if (available < payloadLength)
            {
                // Not enough payload to complete this frame, can we auto-fragment?
                if (configuration.isAutoFragment() && isDataFrame)
                    return autoFragment(buffer, (int)available);

                // Not enough payload, so we have to copy the partial payload.
                // The size of this allocation is limited by the maxFrameSize.
                aggregate = bufferPool.acquire(payloadLength, false);
                aggregate.put(buffer);
                return null;
            }

            if (available == payloadLength)
            {
                // All the available payload is for this frame and completes it.
                RetainableByteBuffer slice = buffer.sliceAndConsume(payloadLength);
                Frame.Parsed frame = newFrame(firstByte, mask, slice, null);
                slice.release();
                state = State.START;
                return frame;
            }

            // The buffer contains data for this frame and for subsequent frames.
            // Copy just the first part of the buffer as the frame payload.
            RetainableByteBuffer slice = buffer.sliceAndConsume(payloadLength);
            Frame.Parsed frame = newFrame(firstByte, mask, slice, null);
            slice.release();
            state = State.START;
            return frame;
        }
        else
        {
            int aggregated = (int)aggregate.remaining();
            int expecting = payloadLength - aggregated;

            if (available < expecting)
            {
                // Not enough payload to complete this frame, just copy it.
                aggregate.put(buffer);
                return null;
            }

            if (available == expecting)
            {
                // All the available payload is for this frame and completes it.
                aggregate.put(buffer);
                state = State.START;
                // Capture the current aggregate to release it.
                RetainableByteBuffer aggregate = this.aggregate;
                return newFrame(firstByte, mask, aggregate, aggregate::release);
            }

            // The buffer contains data for this frame and subsequent frames.
            // Copy just the first part of the buffer as the frame payload.
            RetainableByteBuffer slice = buffer.sliceAndConsume(expecting);
            aggregate.put(slice);
            slice.release();
            state = State.START;
            // Capture the current aggregate to release it.
            RetainableByteBuffer aggregate = this.aggregate;
            return newFrame(firstByte, mask, aggregate, aggregate::release);
        }
    }

    @Override
    public String toString()
    {
        return String
            .format("Parser@%x[s=%s,c=%d,o=0x%x,m=%s,l=%d]", hashCode(), state, cursor, firstByte, mask == null ? "-" : StringUtil.toHexString(mask), payloadLength);
    }
}
