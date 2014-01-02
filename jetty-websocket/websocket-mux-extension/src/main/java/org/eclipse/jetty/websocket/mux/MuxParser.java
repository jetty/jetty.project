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

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;
import org.eclipse.jetty.websocket.mux.op.MuxDropChannel;
import org.eclipse.jetty.websocket.mux.op.MuxFlowControl;
import org.eclipse.jetty.websocket.mux.op.MuxNewChannelSlot;

public class MuxParser
{
    public static interface Listener
    {
        public void onMuxAddChannelRequest(MuxAddChannelRequest request);

        public void onMuxAddChannelResponse(MuxAddChannelResponse response);

        public void onMuxDropChannel(MuxDropChannel drop);

        public void onMuxedFrame(MuxedFrame frame);

        public void onMuxException(MuxException e);

        public void onMuxFlowControl(MuxFlowControl flow);

        public void onMuxNewChannelSlot(MuxNewChannelSlot slot);
    }

    private final static Logger LOG = Log.getLogger(MuxParser.class);

    private MuxedFrame muxframe = new MuxedFrame();
    private MuxParser.Listener events;
    private long channelId;

    public MuxParser.Listener getEvents()
    {
        return events;
    }

    /**
     * Parse the raw {@link WebSocketFrame} payload data for various Mux frames.
     * 
     * @param frame
     *            the WebSocketFrame to parse for mux payload
     */
    public synchronized void parse(Frame frame)
    {
        if (events == null)
        {
            throw new RuntimeException("No " + MuxParser.Listener.class + " specified");
        }

        if (!frame.hasPayload())
        {
            LOG.debug("No payload data, skipping");
            return; // nothing to parse
        }

        if (frame.getOpCode() != OpCode.BINARY)
        {
            LOG.debug("Not a binary opcode (base frame), skipping");
            return; // not a binary opcode
        }

        LOG.debug("Parsing Mux Payload of {}",frame);

        try
        {
            ByteBuffer buffer = frame.getPayload().slice();

            if (buffer.remaining() <= 0)
            {
                return;
            }

            if (frame.getOpCode() == OpCode.CONTINUATION)
            {
                muxframe.reset();
                muxframe.setFin(frame.isFin());
                muxframe.setFin(frame.isRsv1());
                muxframe.setFin(frame.isRsv2());
                muxframe.setFin(frame.isRsv3());
                muxframe.setIsContinuation();
                parseDataFramePayload(buffer);
            }
            else
            {
                // new frame
                channelId = readChannelId(buffer);
                if (channelId == 0)
                {
                    parseControlBlocks(buffer);
                }
                else
                {
                    parseDataFrame(buffer);
                }
            }
        }
        catch (MuxException e)
        {
            events.onMuxException(e);
        }
        catch (Throwable t)
        {
            events.onMuxException(new MuxException(t));
        }
    }

    private void parseControlBlocks(ByteBuffer buffer)
    {
        // process the remaining buffer here.
        while (buffer.remaining() > 0)
        {
            byte b = buffer.get();
            byte opc = (byte)((byte)(b >> 5) & 0xFF);
            b = (byte)(b & 0x1F);

            try {
                switch (opc)
                {
                    case MuxOp.ADD_CHANNEL_REQUEST:
                    {
                        MuxAddChannelRequest op = new MuxAddChannelRequest();
                        op.setRsv((byte)((b & 0x1C) >> 2));
                        op.setEncoding((byte)(b & 0x03));
                        op.setChannelId(readChannelId(buffer));
                        long handshakeSize = read139EncodedSize(buffer);
                        op.setHandshake(readBlock(buffer,handshakeSize));
                        events.onMuxAddChannelRequest(op);
                        break;
                    }
                    case MuxOp.ADD_CHANNEL_RESPONSE:
                    {
                        MuxAddChannelResponse op = new MuxAddChannelResponse();
                        op.setFailed((b & 0x10) != 0);
                        op.setRsv((byte)((byte)(b & 0x0C) >> 2));
                        op.setEncoding((byte)(b & 0x03));
                        op.setChannelId(readChannelId(buffer));
                        long handshakeSize = read139EncodedSize(buffer);
                        op.setHandshake(readBlock(buffer,handshakeSize));
                        events.onMuxAddChannelResponse(op);
                        break;
                    }
                    case MuxOp.DROP_CHANNEL:
                    {
                        int rsv = (b & 0x1F);
                        long channelId = readChannelId(buffer);
                        long reasonSize = read139EncodedSize(buffer);
                        ByteBuffer reasonBuf = readBlock(buffer,reasonSize);
                        MuxDropChannel op = MuxDropChannel.parse(channelId,reasonBuf);
                        op.setRsv(rsv);
                        events.onMuxDropChannel(op);
                        break;
                    }
                    case MuxOp.FLOW_CONTROL:
                    {
                        MuxFlowControl op = new MuxFlowControl();
                        op.setRsv((byte)(b & 0x1F));
                        op.setChannelId(readChannelId(buffer));
                        op.setSendQuotaSize(read139EncodedSize(buffer));
                        events.onMuxFlowControl(op);
                        break;
                    }
                    case MuxOp.NEW_CHANNEL_SLOT:
                    {
                        MuxNewChannelSlot op = new MuxNewChannelSlot();
                        op.setRsv((byte)((b & 0x1E) >> 1));
                        op.setFallback((b & 0x01) != 0);
                        op.setNumberOfSlots(read139EncodedSize(buffer));
                        op.setInitialSendQuota(read139EncodedSize(buffer));
                        events.onMuxNewChannelSlot(op);
                        break;
                    }
                    default:
                    {
                        String err = String.format("Unknown Mux Control Code OPC [0x%X]",opc);
                        throw new MuxException(err);
                    }
                }
            }
            catch (Throwable t)
            {
                LOG.warn(t);
                throw new MuxException(t);
            }
        }
    }

    private void parseDataFrame(ByteBuffer buffer)
    {
        byte b = buffer.get();
        boolean fin = ((b & 0x80) != 0);
        boolean rsv1 = ((b & 0x40) != 0);
        boolean rsv2 = ((b & 0x20) != 0);
        boolean rsv3 = ((b & 0x10) != 0);
        byte opcode = (byte)(b & 0x0F);

        if (opcode == OpCode.CONTINUATION)
        {
            muxframe.setIsContinuation();
        }
        else
        {
            muxframe.reset();
            muxframe.setOp(opcode);
        }

        muxframe.setChannelId(channelId);
        muxframe.setFin(fin);
        muxframe.setRsv1(rsv1);
        muxframe.setRsv2(rsv2);
        muxframe.setRsv3(rsv3);

        parseDataFramePayload(buffer);
    }

    private void parseDataFramePayload(ByteBuffer buffer)
    {
        int capacity = buffer.remaining();
        ByteBuffer payload = ByteBuffer.allocate(capacity);
        payload.put(buffer);
        BufferUtil.flipToFlush(payload,0);
        muxframe.setPayload(payload);
        try
        {
            LOG.debug("notifyFrame() - {}",muxframe);
            events.onMuxedFrame(muxframe);
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
    }

    /**
     * Per section <a href="https://tools.ietf.org/html/draft-ietf-hybi-websocket-multiplexing#section-9.1">9.1. Number Encoding in Multiplex Control
     * Blocks</a>, read the 1/3/9 byte length using <a href="https://tools.ietf.org/html/rfc6455#section-5.2">Section 5.2 of RFC 6455</a>.
     * 
     * @param buffer
     *            the buffer to read from
     * @return the decoded size
     * @throws MuxException
     *             when the encoding does not make sense per the spec, or it is a value above {@link Long#MAX_VALUE}
     */
    public long read139EncodedSize(ByteBuffer buffer)
    {
        long ret = -1;
        long minValue = 0x00; // used to validate minimum # of bytes (per spec)
        int cursor = 0;

        byte b = buffer.get();
        ret = (b & 0x7F);

        if (ret == 0x7F)
        {
            // 9 byte length
            ret = 0;
            minValue = 0xFF_FF;
            cursor = 8;
        }
        else if (ret == 0x7E)
        {
            // 3 byte length
            ret = 0;
            minValue = 0x7F;
            cursor = 2;
        }
        else
        {
            // 1 byte length
            // no validation of minimum bytes needed here
            return ret;
        }

        // parse multi-byte length
        while (cursor > 0)
        {
            ret = ret << 8;
            b = buffer.get();
            ret |= (b & 0xFF);
            --cursor;
        }

        // validate minimum value per spec.
        if (ret <= minValue)
        {
            String err = String.format("Invalid 1/3/9 length 0x%X (minimum value for chosen encoding is 0x%X)",ret,minValue);
            throw new MuxException(err);
        }

        return ret;
    }

    private ByteBuffer readBlock(ByteBuffer buffer, long size)
    {
        if (size == 0)
        {
            return null;
        }

        if (size > buffer.remaining())
        {
            String err = String.format("Truncated data, expected %,d byte(s), but only %,d byte(s) remain",size,buffer.remaining());
            throw new MuxException(err);
        }

        if (size > Integer.MAX_VALUE)
        {
            String err = String.format("[Int-Sane!] Buffer size %,d is too large to be supported (max allowed is %,d)",size,Integer.MAX_VALUE);
            throw new MuxException(err);
        }

        ByteBuffer ret = ByteBuffer.allocate((int)size);
        BufferUtil.put(buffer,ret);
        BufferUtil.flipToFlush(ret,0);
        return ret;
    }

    /**
     * Read Channel ID using <a href="https://tools.ietf.org/html/draft-ietf-hybi-websocket-multiplexing#section-7">Section 7. Framing</a> techniques
     * 
     * @param buffer
     *            the buffer to parse from.
     * @return the channel Id
     * @throws MuxException
     *             when the encoding does not make sense per the spec.
     */
    public long readChannelId(ByteBuffer buffer)
    {
        long id = -1;
        long minValue = 0x00; // used to validate minimum # of bytes (per spec)
        byte b = buffer.get();
        int cursor = -1;
        if ((b & 0x80) == 0)
        {
            // 7 bit channel id
            // no validation of minimum bytes needed here
            return (b & 0x7F);
        }
        else if ((b & 0x40) == 0)
        {
            // 14 bit channel id
            id = (b & 0x3F);
            minValue = 0x7F;
            cursor = 1;
        }
        else if ((b & 0x20) == 0)
        {
            // 21 bit channel id
            id = (b & 0x1F);
            minValue = 0x3F_FF;
            cursor = 2;
        }
        else
        {
            // 29 bit channel id
            id = (b & 0x1F);
            minValue = 0x1F_FF_FF;
            cursor = 3;
        }

        while (cursor > 0)
        {
            id = id << 8;
            b = buffer.get();
            id |= (b & 0xFF);
            --cursor;
        }

        // validate minimum value per spec.
        if (id <= minValue)
        {
            String err = String.format("Invalid Channel ID 0x%X (minimum value for chosen encoding is 0x%X)",id,minValue);
            throw new MuxException(err);
        }

        return id;
    }

    public void setEvents(MuxParser.Listener events)
    {
        this.events = events;
    }
}
