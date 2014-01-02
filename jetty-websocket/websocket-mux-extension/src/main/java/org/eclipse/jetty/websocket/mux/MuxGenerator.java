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

package org.eclipse.jetty.websocket.mux;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;
import org.eclipse.jetty.websocket.mux.op.MuxDropChannel;
import org.eclipse.jetty.websocket.mux.op.MuxFlowControl;
import org.eclipse.jetty.websocket.mux.op.MuxNewChannelSlot;

/**
 * Generate Mux frames destined for the physical connection.
 */
public class MuxGenerator
{
    private static final int CONTROL_BUFFER_SIZE = 2 * 1024;
    /** 4 bytes for channel ID + 1 for fin/rsv/opcode */
    private static final int DATA_FRAME_OVERHEAD = 5;
    private ByteBufferPool bufferPool;
    private OutgoingFrames outgoing;

    public MuxGenerator()
    {
        this(new ArrayByteBufferPool());
    }

    public MuxGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    public void generate(long channelId, Frame frame, WriteCallback callback)
    {
        ByteBuffer muxPayload = bufferPool.acquire(frame.getPayloadLength() + DATA_FRAME_OVERHEAD,false);
        BufferUtil.flipToFill(muxPayload);

        // start building mux payload
        writeChannelId(muxPayload,channelId);
        byte b = (byte)(frame.isFin()?0x80:0x00); // fin
        b |= (byte)(frame.isRsv1()?0x40:0x00); // rsv1
        b |= (byte)(frame.isRsv2()?0x20:0x00); // rsv2
        b |= (byte)(frame.isRsv3()?0x10:0x00); // rsv3
        b |= (byte)(frame.getOpCode() & 0x0F); // opcode
        muxPayload.put(b);
        BufferUtil.put(frame.getPayload(),muxPayload);

        // build muxed frame
        WebSocketFrame muxFrame = new BinaryFrame();
        BufferUtil.flipToFlush(muxPayload,0);
        muxFrame.setPayload(muxPayload);
        // NOTE: the physical connection will handle masking rules for this frame.

        // release original buffer (no longer needed)
        bufferPool.release(frame.getPayload());

        // send muxed frame down to the physical connection.
        outgoing.outgoingFrame(muxFrame,callback);
    }

    public void generate(WriteCallback callback,MuxControlBlock... blocks) throws IOException
    {
        if ((blocks == null) || (blocks.length <= 0))
        {
            return; // nothing to do
        }

        ByteBuffer payload = bufferPool.acquire(CONTROL_BUFFER_SIZE,false);
        BufferUtil.flipToFill(payload);

        writeChannelId(payload,0); // control channel

        for (MuxControlBlock block : blocks)
        {
            switch (block.getOpCode())
            {
                case MuxOp.ADD_CHANNEL_REQUEST:
                {
                    MuxAddChannelRequest op = (MuxAddChannelRequest)block;
                    byte b = (byte)((op.getOpCode() & 0x07) << 5); // opcode
                    b |= (byte)((op.getRsv() & 0x07) << 2); // rsv
                    b |= (op.getEncoding() & 0x03); // enc
                    payload.put(b); // opcode + rsv + enc
                    writeChannelId(payload,op.getChannelId());
                    write139Buffer(payload,op.getHandshake());
                    break;
                }
                case MuxOp.ADD_CHANNEL_RESPONSE:
                {
                    MuxAddChannelResponse op = (MuxAddChannelResponse)block;
                    byte b = (byte)((op.getOpCode() & 0x07) << 5); // opcode
                    b |= (op.isFailed()?0x10:0x00); // failure bit
                    b |= (byte)((op.getRsv() & 0x03) << 2); // rsv
                    b |= (op.getEncoding() & 0x03); // enc
                    payload.put(b); // opcode + f + rsv + enc
                    writeChannelId(payload,op.getChannelId());
                    if (op.getHandshake() != null)
                    {
                        write139Buffer(payload,op.getHandshake());
                    }
                    else
                    {
                        // no handshake details
                        write139Size(payload,0);
                    }
                    break;
                }
                case MuxOp.DROP_CHANNEL:
                {
                    MuxDropChannel op = (MuxDropChannel)block;
                    byte b = (byte)((op.getOpCode() & 0x07) << 5); // opcode
                    b |= (byte)(op.getRsv() & 0x1F); // rsv
                    payload.put(b); // opcode + rsv
                    writeChannelId(payload,op.getChannelId());
                    write139Buffer(payload,op.asReasonBuffer());
                    break;
                }
                case MuxOp.FLOW_CONTROL:
                {
                    MuxFlowControl op = (MuxFlowControl)block;
                    byte b = (byte)((op.getOpCode() & 0x07) << 5); // opcode
                    b |= (byte)(op.getRsv() & 0x1F); // rsv
                    payload.put(b); // opcode + rsv
                    writeChannelId(payload,op.getChannelId());
                    write139Size(payload,op.getSendQuotaSize());
                    break;
                }
                case MuxOp.NEW_CHANNEL_SLOT:
                {
                    MuxNewChannelSlot op = (MuxNewChannelSlot)block;
                    byte b = (byte)((op.getOpCode() & 0x07) << 5); // opcode
                    b |= (byte)(op.getRsv() & 0x0F) << 1; // rsv
                    b |= (byte)(op.isFallback()?0x01:0x00); // fallback bit
                    payload.put(b); // opcode + rsv + fallback bit
                    write139Size(payload,op.getNumberOfSlots());
                    write139Size(payload,op.getInitialSendQuota());
                    break;
                }
            }
        }
        BufferUtil.flipToFlush(payload,0);
        WebSocketFrame frame = new BinaryFrame();
        frame.setPayload(payload);
        outgoing.outgoingFrame(frame,callback);
    }

    public OutgoingFrames getOutgoing()
    {
        return outgoing;
    }

    public void setOutgoing(OutgoingFrames outgoing)
    {
        this.outgoing = outgoing;
    }

    /**
     * Write a 1/3/9 encoded size, then a byte buffer of that size.
     * 
     * @param payload
     * @param buffer
     */
    public void write139Buffer(ByteBuffer payload, ByteBuffer buffer)
    {
        write139Size(payload,buffer.remaining());
        writeBuffer(payload,buffer);
    }

    /**
     * Write a 1/3/9 encoded size.
     * 
     * @param payload
     * @param size
     */
    public void write139Size(ByteBuffer payload, long size)
    {
        if (size > 0xFF_FF)
        {
            // 9 byte encoded
            payload.put((byte)0x7F);
            payload.putLong(size);
            return;
        }

        if (size >= 0x7E)
        {
            // 3 byte encoded
            payload.put((byte)0x7E);
            payload.put((byte)(size >> 8));
            payload.put((byte)(size & 0xFF));
            return;
        }

        // 1 byte (7 bit) encoded
        payload.put((byte)(size & 0x7F));
    }

    public void writeBuffer(ByteBuffer payload, ByteBuffer buffer)
    {
        BufferUtil.put(buffer,payload);
    }

    /**
     * Write multiplexing channel id, using logical channel id encoding (of 1,2,3, or 4 octets)
     * 
     * @param payload
     * @param channelId
     */
    public void writeChannelId(ByteBuffer payload, long channelId)
    {
        if (channelId > 0x1F_FF_FF_FF)
        {
            throw new MuxException("Illegal Channel ID: too big");
        }

        if (channelId > 0x1F_FF_FF)
        {
            // 29 bit channel id (4 bytes)
            payload.put((byte)(0xE0 | ((channelId >> 24) & 0x1F)));
            payload.put((byte)((channelId >> 16) & 0xFF));
            payload.put((byte)((channelId >> 8) & 0xFF));
            payload.put((byte)(channelId & 0xFF));
            return;
        }

        if (channelId > 0x3F_FF)
        {
            // 21 bit channel id (3 bytes)
            payload.put((byte)(0xC0 | ((channelId >> 16) & 0x1F)));
            payload.put((byte)((channelId >> 8) & 0xFF));
            payload.put((byte)(channelId & 0xFF));
            return;
        }

        if (channelId > 0x7F)
        {
            // 14 bit channel id (2 bytes)
            payload.put((byte)(0x80 | ((channelId >> 8) & 0x3F)));
            payload.put((byte)(channelId & 0xFF));
            return;
        }

        // 7 bit channel id
        payload.put((byte)(channelId & 0x7F));
    }
}
