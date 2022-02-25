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

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Frame;

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

    /**
     * Generate the whole frame (header + payload copy) into a single ByteBuffer.
     * @param frame the frame to generate.
     * @param buffer the buffer to output the generated frame to.
     */
    public void generateWholeFrame(Frame frame, ByteBuffer buffer)
    {
        generateHeader(frame, buffer);
        generatePayload(frame, buffer);
    }

    /**
     * Generate the header bytes of a frame into a single ByteBuffer.
     * @param frame the frame to generate.
     * @param buffer the buffer to output the generated frame to.
     */
    public void generateHeader(Frame frame, ByteBuffer buffer)
    {
        int pos = BufferUtil.flipToFill(buffer);

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

        byte opcode = frame.getOpCode();
        b |= opcode & 0x0F;
        buffer.put(b);

        // is masked
        b = (frame.isMasked() ? (byte)0x80 : (byte)0x00);

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
        if (frame.isMasked())
            buffer.put(frame.getMask());

        BufferUtil.flipToFlush(buffer, pos);
    }

    /**
     * Generate the payload of a frame into a single ByteBuffer, if the frame has a mask the payload
     * will be masked as it is copied to the output buffer.
     * @param frame the frame to generate.
     * @param buffer the buffer to output the generated frame to.
     */
    public void generatePayload(Frame frame, ByteBuffer buffer)
    {
        ByteBuffer payload = frame.getPayload();
        if (!BufferUtil.hasContent(payload))
            return;

        int pos = BufferUtil.flipToFill(buffer);
        if (frame.isMasked())
            maskPayload(buffer, frame);
        else
            buffer.put(payload.slice());
        BufferUtil.flipToFlush(buffer, pos);
    }

    private void maskPayload(ByteBuffer buffer, Frame frame)
    {
        byte[] mask = frame.getMask();
        int maskInt = 0;
        for (byte maskByte : mask)
        {
            maskInt = (maskInt << 8) + (maskByte & 0xFF);
        }

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
                    buffer.putInt(payload.getInt(start) ^ maskInt);
                    start += 4;
                }
                else
                {
                    buffer.put((byte)(payload.get(start) ^ mask[maskOffset & 3]));
                    ++start;
                    ++maskOffset;
                }
            }
        }
    }
}
