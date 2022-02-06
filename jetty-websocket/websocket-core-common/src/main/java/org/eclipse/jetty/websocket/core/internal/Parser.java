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

package org.eclipse.jetty.websocket.core.internal;

import java.io.Closeable;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
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
    private final ByteBufferPool bufferPool;
    private final Configuration configuration;

    // State specific
    private State state = State.START;
    private byte firstByte;
    private int cursor;
    private byte[] mask;
    private int payloadLength;
    private ByteBuffer aggregate;

    public Parser(ByteBufferPool bufferPool)
    {
        this(bufferPool, new Configuration.ConfigurationCustomizer());
    }

    public Parser(ByteBufferPool bufferPool, Configuration configuration)
    {
        this.bufferPool = bufferPool;
        this.configuration = configuration;
    }

    public void reset()
    {
        state = State.START;
        firstByte = 0;
        mask = null;
        cursor = 0;
        aggregate = null;
        payloadLength = -1;
    }

    /**
     * Parse the buffer.
     *
     * @param buffer the buffer to parse from.
     * @return Frame or null if not enough data for a complete frame.
     * @throws WebSocketException if unable to parse properly
     */
    public ParsedFrame parse(ByteBuffer buffer) throws WebSocketException
    {
        try
        {
            // parse through
            while (buffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} Parsing {}", this, BufferUtil.toDetailString(buffer));

                switch (state)
                {
                    case START:
                    {
                        // peek at byte
                        firstByte = buffer.get();
                        state = State.PAYLOAD_LEN;
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
                            return newFrame(firstByte, mask, null, false);
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
                        payloadLength |= (b & 0xFF) << (8 * cursor);
                        if (cursor == 0)
                        {
                            if (mask != null)
                            {
                                state = State.MASK;
                            }
                            else if (payloadLength == 0)
                            {
                                state = State.START;
                                return newFrame(firstByte, mask, null, false);
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
                                return newFrame(firstByte, mask, null, false);
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
                                return newFrame(firstByte, mask, null, false);
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
                        ParsedFrame frame = parsePayload(buffer);
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
                LOG.debug("{} Parse Error {}", this, BufferUtil.toDetailString(buffer), t);

            buffer.position(buffer.limit()); // consume remaining

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

    protected void checkFrameSize(byte opcode, int payloadLength) throws MessageTooLargeException, ProtocolException
    {
        if (OpCode.isControlFrame(opcode))
        {
            if (payloadLength > Frame.MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");
        }
        else
        {
            long maxFrameSize = configuration.getMaxFrameSize();
            if (!configuration.isAutoFragment() && maxFrameSize > 0 && payloadLength > maxFrameSize)
                throw new MessageTooLargeException("Cannot handle payload lengths larger than " + maxFrameSize);
        }
    }

    protected ParsedFrame newFrame(byte firstByte, byte[] mask, ByteBuffer payload, boolean releaseable)
    {
        // Validate OpCode
        byte opcode = OpCode.getOpCode(firstByte);
        if (!OpCode.isKnown(opcode))
            throw new ProtocolException("Unknown opcode: " + opcode);

        // Validate Control Frame
        boolean fin = ((firstByte & 0x80) != 0);
        if (OpCode.isControlFrame(opcode) && !fin)
            throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(opcode) + "]");

        return new ParsedFrame(firstByte, mask, payload, releaseable);
    }

    private ParsedFrame autoFragment(ByteBuffer buffer, int fragmentSize)
    {
        payloadLength -= fragmentSize;

        byte[] nextMask = null;
        if (mask != null)
        {
            int shift = fragmentSize % 4;
            nextMask = new byte[4];
            nextMask[0] = mask[(0 + shift) % 4];
            nextMask[1] = mask[(1 + shift) % 4];
            nextMask[2] = mask[(2 + shift) % 4];
            nextMask[3] = mask[(3 + shift) % 4];
        }

        ByteBuffer content = buffer.slice();
        content.limit(fragmentSize);
        buffer.position(buffer.position() + fragmentSize);

        final ParsedFrame frame = newFrame((byte)(firstByte & 0x7F), mask, content, false);
        mask = nextMask;
        firstByte = (byte)((firstByte & 0x80) | OpCode.CONTINUATION);
        state = State.FRAGMENT;
        return frame;
    }

    private ParsedFrame parsePayload(ByteBuffer buffer)
    {
        if (payloadLength == 0)
            return null;

        if (BufferUtil.isEmpty(buffer))
            return null;

        int available = buffer.remaining();
        boolean isDataFrame = OpCode.isDataFrame(OpCode.getOpCode(firstByte));

        // Always autoFragment data frames if payloadLength is greater than maxFrameSize.
        long maxFrameSize = configuration.getMaxFrameSize();
        if (maxFrameSize > 0 && isDataFrame && payloadLength > maxFrameSize)
            return autoFragment(buffer, (int)Math.min(available, maxFrameSize));

        if (aggregate == null)
        {
            if (available < payloadLength)
            {
                // not enough to complete this frame
                // Can we auto-fragment
                if (configuration.isAutoFragment() && isDataFrame)
                    return autoFragment(buffer, available);

                // No space in the buffer, so we have to copy the partial payload
                aggregate = bufferPool.acquire(payloadLength, false);
                BufferUtil.append(aggregate, buffer);
                return null;
            }

            if (available == payloadLength)
            {
                // All the available data is for this frame and completes it 
                ParsedFrame frame = newFrame(firstByte, mask, buffer.slice(), false);
                buffer.position(buffer.limit());
                state = State.START;
                return frame;
            }

            // The buffer contains all the data for this frame and for subsequent frames
            // Copy the just the first part of the buffer as frame payload
            int limit = buffer.limit();
            int end = buffer.position() + payloadLength;
            buffer.limit(end);
            final ParsedFrame frame = newFrame(firstByte, mask, buffer.slice(), false);
            buffer.position(end);
            buffer.limit(limit);
            state = State.START;
            return frame;
        }
        else
        {
            int aggregated = aggregate.remaining();
            int expecting = payloadLength - aggregated;

            if (available < expecting)
            {
                // not enough data to complete this frame, just copy it
                BufferUtil.append(aggregate, buffer);
                return null;
            }

            if (available == expecting)
            {
                // All the available data is for this frame and completes it
                BufferUtil.append(aggregate, buffer);
                state = State.START;
                return newFrame(firstByte, mask, aggregate, true);
            }

            // The buffer contains data for this frame and subsequent frames
            // Copy the first part of the buffer to the frame and complete it
            int limit = buffer.limit();
            buffer.limit(buffer.position() + expecting);
            BufferUtil.append(aggregate, buffer);
            buffer.limit(limit);
            state = State.START;
            return newFrame(firstByte, mask, aggregate, true);
        }
    }

    @Override
    public String toString()
    {
        return String
            .format("Parser@%x[s=%s,c=%d,o=0x%x,m=%s,l=%d]", hashCode(), state, cursor, firstByte, mask == null ? "-" : TypeUtil.toHexString(mask), payloadLength);
    }

    public class ParsedFrame extends Frame implements Closeable, CloseStatus.Supplier
    {
        final CloseStatus closeStatus;
        final boolean releaseable;

        public ParsedFrame(byte firstByte, byte[] mask, ByteBuffer payload, boolean releaseable)
        {
            super(firstByte, mask, payload);
            demask();
            this.releaseable = releaseable;
            if (getOpCode() == OpCode.CLOSE)
            {
                if (hasPayload())
                    closeStatus = new CloseStatus(payload.duplicate());
                else
                    closeStatus = CloseStatus.NO_CODE_STATUS;
            }
            else
            {
                closeStatus = null;
            }
        }

        @Override
        public void close()
        {
            if (releaseable)
                bufferPool.release(getPayload());
        }

        @Override
        public CloseStatus getCloseStatus()
        {
            return closeStatus;
        }

        public boolean isReleaseable()
        {
            return releaseable;
        }

        @Override
        public String toString()
        {
            if (closeStatus == null)
                return super.toString();
            return super.toString() + ":" + closeStatus;
        }
    }
}
