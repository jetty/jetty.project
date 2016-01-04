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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * Generating a frame in WebSocket land.
 * 
 * <pre>
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *   +-+-+-+-+-------+-+-------------+-------------------------------+
 *   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *   | |1|2|3|       |K|             |                               |
 *   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *   |     Extended payload length continued, if payload len == 127  |
 *   + - - - - - - - - - - - - - - - +-------------------------------+
 *   |                               |Masking-key, if MASK set to 1  |
 *   +-------------------------------+-------------------------------+
 *   | Masking-key (continued)       |          Payload Data         |
 *   +-------------------------------- - - - - - - - - - - - - - - - +
 *   :                     Payload Data continued ...                :
 *   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *   |                     Payload Data continued ...                |
 *   +---------------------------------------------------------------+
 * </pre>
 */
public class Generator
{
    /**
     * The overhead (maximum) for a framing header. Assuming a maximum sized payload with masking key.
     */
    public static final int MAX_HEADER_LENGTH = 28;

    private final WebSocketBehavior behavior;
    private final ByteBufferPool bufferPool;
    private final boolean validating;
    private final boolean readOnly;

    /**
     * Are any flags in use
     * <p>
     * 
     * <pre>
     *   0100_0000 (0x40) = rsv1
     *   0010_0000 (0x20) = rsv2
     *   0001_0000 (0x10) = rsv3
     * </pre>
     */
    private byte flagsInUse = 0x00;

    /**
     * Construct Generator with provided policy and bufferPool
     * 
     * @param policy
     *            the policy to use
     * @param bufferPool
     *            the buffer pool to use
     */
    public Generator(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this(policy,bufferPool,true,false);
    }

    /**
     * Construct Generator with provided policy and bufferPool
     * 
     * @param policy
     *            the policy to use
     * @param bufferPool
     *            the buffer pool to use
     * @param validating
     *            true to enable RFC frame validation
     */
    public Generator(WebSocketPolicy policy, ByteBufferPool bufferPool, boolean validating)
    {
        this(policy,bufferPool,validating,false);
    }

    /**
     * Construct Generator with provided policy and bufferPool
     * 
     * @param policy
     *            the policy to use
     * @param bufferPool
     *            the buffer pool to use
     * @param validating
     *            true to enable RFC frame validation
     * @param readOnly
     *            true if generator is to treat frames as read-only and not modify them. Useful for debugging purposes, but not generally for runtime use.
     */
    public Generator(WebSocketPolicy policy, ByteBufferPool bufferPool, boolean validating, boolean readOnly)
    {
        this.behavior = policy.getBehavior();
        this.bufferPool = bufferPool;
        this.validating = validating;
        this.readOnly = readOnly;
    }

    public void assertFrameValid(Frame frame)
    {
        if (!validating)
        {
            return;
        }

        /*
         * RFC 6455 Section 5.2
         * 
         * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
         * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
         */
        if (frame.isRsv1() && !isRsv1InUse())
        {
            throw new ProtocolException("RSV1 not allowed to be set");
        }

        if (frame.isRsv2() && !isRsv2InUse())
        {
            throw new ProtocolException("RSV2 not allowed to be set");
        }

        if (frame.isRsv3() && !isRsv3InUse())
        {
            throw new ProtocolException("RSV3 not allowed to be set");
        }

        if (OpCode.isControlFrame(frame.getOpCode()))
        {
            /*
             * RFC 6455 Section 5.5
             * 
             * All control frames MUST have a payload length of 125 bytes or less and MUST NOT be fragmented.
             */
            if (frame.getPayloadLength() > 125)
            {
                throw new ProtocolException("Invalid control frame payload length");
            }

            if (!frame.isFin())
            {
                throw new ProtocolException("Control Frames must be FIN=true");
            }

            /*
             * RFC 6455 Section 5.5.1
             * 
             * close frame payload is specially formatted which is checked in CloseInfo
             */
            if (frame.getOpCode() == OpCode.CLOSE)
            {

                ByteBuffer payload = frame.getPayload();
                if (payload != null)
                {
                    new CloseInfo(payload,true);
                }
            }
        }
    }

    public void configureFromExtensions(List<? extends Extension> exts)
    {
        // default
        flagsInUse = 0x00;

        // configure from list of extensions in use
        for (Extension ext : exts)
        {
            if (ext.isRsv1User())
            {
                flagsInUse = (byte)(flagsInUse | 0x40);
            }
            if (ext.isRsv2User())
            {
                flagsInUse = (byte)(flagsInUse | 0x20);
            }
            if (ext.isRsv3User())
            {
                flagsInUse = (byte)(flagsInUse | 0x10);
            }
        }
    }

    public ByteBuffer generateHeaderBytes(Frame frame)
    {
        ByteBuffer buffer = bufferPool.acquire(MAX_HEADER_LENGTH,true);
        generateHeaderBytes(frame,buffer);
        return buffer;
    }

    public void generateHeaderBytes(Frame frame, ByteBuffer buffer)
    {
        int p = BufferUtil.flipToFill(buffer);

        // we need a framing header
        assertFrameValid(frame);

        /*
         * start the generation process
         */
        byte b = 0x00;

        // Setup fin thru opcode
        if (frame.isFin())
        {
            b |= 0x80; // 1000_0000
        }

        // Set the flags
        if (frame.isRsv1())
        {
            b |= 0x40; // 0100_0000
        }
        if (frame.isRsv2())
        {
            b |= 0x20; // 0010_0000
        }
        if (frame.isRsv3())
        {
            b |= 0x10; // 0001_0000
        }

        // NOTE: using .getOpCode() here, not .getType().getOpCode() for testing reasons
        byte opcode = frame.getOpCode();

        if (frame.getOpCode() == OpCode.CONTINUATION)
        {
            // Continuations are not the same OPCODE
            opcode = OpCode.CONTINUATION;
        }

        b |= opcode & 0x0F;

        buffer.put(b);

        // is masked
        b = (frame.isMasked()?(byte)0x80:(byte)0x00);

        // payload lengths
        int payloadLength = frame.getPayloadLength();

        /*
         * if length is over 65535 then its a 7 + 64 bit length
         */
        if (payloadLength > 0xFF_FF)
        {
            // we have a 64 bit length
            b |= 0x7F;
            buffer.put(b); // indicate 8 byte length
            buffer.put((byte)0); //
            buffer.put((byte)0); // anything over an
            buffer.put((byte)0); // int is just
            buffer.put((byte)0); // insane!
            buffer.put((byte)((payloadLength >> 24) & 0xFF));
            buffer.put((byte)((payloadLength >> 16) & 0xFF));
            buffer.put((byte)((payloadLength >> 8) & 0xFF));
            buffer.put((byte)(payloadLength & 0xFF));
        }
        /*
         * if payload is greater that 126 we have a 7 + 16 bit length
         */
        else if (payloadLength >= 0x7E)
        {
            b |= 0x7E;
            buffer.put(b); // indicate 2 byte length
            buffer.put((byte)(payloadLength >> 8));
            buffer.put((byte)(payloadLength & 0xFF));
        }
        /*
         * we have a 7 bit length
         */
        else
        {
            b |= (payloadLength & 0x7F);
            buffer.put(b);
        }

        // masking key
        if (frame.isMasked() && !readOnly)
        {
            byte[] mask = frame.getMask();
            buffer.put(mask);
            int maskInt = 0;
            for (byte maskByte : mask)
                maskInt = (maskInt << 8) + (maskByte & 0xFF);

            // perform data masking here
            ByteBuffer payload = frame.getPayload();
            if ((payload != null) && (payload.remaining() > 0))
            {
                int maskOffset = 0;
                int start = payload.position();
                int end = payload.limit();
                int remaining;
                while ((remaining = end - start) > 0)
                {
                    if (remaining >= 4)
                    {
                        payload.putInt(start,payload.getInt(start) ^ maskInt);
                        start += 4;
                    }
                    else
                    {
                        payload.put(start,(byte)(payload.get(start) ^ mask[maskOffset & 3]));
                        ++start;
                        ++maskOffset;
                    }
                }
            }
        }

        BufferUtil.flipToFlush(buffer,p);
    }

    /**
     * Generate the whole frame (header + payload copy) into a single ByteBuffer.
     * <p>
     * Note: This is slow, moves lots of memory around. Only use this if you must (such as in unit testing).
     * 
     * @param frame
     *            the frame to generate
     * @param buf
     *            the buffer to output the generated frame to
     */
    public void generateWholeFrame(Frame frame, ByteBuffer buf)
    {
        buf.put(generateHeaderBytes(frame));
        if (frame.hasPayload())
        {
            if (readOnly)
            {
                buf.put(frame.getPayload().slice());
            }
            else
            {
                buf.put(frame.getPayload());
            }
        }
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public void setRsv1InUse(boolean rsv1InUse)
    {
        if (readOnly)
        {
            throw new RuntimeException("Not allowed to modify read-only frame");
        }
        flagsInUse = (byte)((flagsInUse & 0xBF) | (rsv1InUse?0x40:0x00));
    }

    public void setRsv2InUse(boolean rsv2InUse)
    {
        if (readOnly)
        {
            throw new RuntimeException("Not allowed to modify read-only frame");
        }
        flagsInUse = (byte)((flagsInUse & 0xDF) | (rsv2InUse?0x20:0x00));
    }

    public void setRsv3InUse(boolean rsv3InUse)
    {
        if (readOnly)
        {
            throw new RuntimeException("Not allowed to modify read-only frame");
        }
        flagsInUse = (byte)((flagsInUse & 0xEF) | (rsv3InUse?0x10:0x00));
    }

    public boolean isRsv1InUse()
    {
        return (flagsInUse & 0x40) != 0;
    }

    public boolean isRsv2InUse()
    {
        return (flagsInUse & 0x20) != 0;
    }

    public boolean isRsv3InUse()
    {
        return (flagsInUse & 0x10) != 0;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Generator[");
        builder.append(behavior);
        if (validating)
        {
            builder.append(",validating");
        }
        if (isRsv1InUse())
        {
            builder.append(",+rsv1");
        }
        if (isRsv2InUse())
        {
            builder.append(",+rsv2");
        }
        if (isRsv3InUse())
        {
            builder.append(",+rsv3");
        }
        builder.append("]");
        return builder.toString();
    }
}
