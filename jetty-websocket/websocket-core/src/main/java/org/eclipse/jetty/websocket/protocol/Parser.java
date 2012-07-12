// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.protocol;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.io.IncomingFrames;

/**
 * Parsing of a frames in WebSocket land.
 */
public class Parser
{
    private enum State
    {
        START,
        FINOP,
        PAYLOAD_LEN,
        PAYLOAD_LEN_BYTES,
        MASK,
        MASK_BYTES,
        PAYLOAD
    }

    // State specific
    private State state = State.START;
    private int cursor = 0;
    // Frame
    private WebSocketFrame frame;
    // payload specific
    private ByteBuffer payload;
    private int payloadLength;

    private static final Logger LOG = Log.getLogger(Parser.class);
    private IncomingFrames incomingFramesHandler;
    private WebSocketPolicy policy;

    public Parser(WebSocketPolicy wspolicy)
    {
        /*
         * TODO: Investigate addition of decompression factory similar to SPDY work in situation of negotiated deflate extension?
         */

        this.policy = wspolicy;
    }

    private void assertSanePayloadLength(long len)
    {
        LOG.debug("Payload Length: " + len);
        // Since we use ByteBuffer so often, having lengths over Integer.MAX_VALUE is really impossible.
        if (len > Integer.MAX_VALUE)
        {
            // OMG! Sanity Check! DO NOT WANT! Won't anyone think of the memory!
            throw new MessageTooLargeException("[int-sane!] cannot handle payload lengths larger than " + Integer.MAX_VALUE);
        }
        policy.assertValidPayloadLength((int)len);

        switch (frame.getOpCode())
        {
            case CLOSE:
                if (len == 1)
                {
                    throw new ProtocolException("Invalid close frame payload length, [" + payloadLength + "]");
                }
                // fall thru
            case PING:
            case PONG:
                if (len > WebSocketFrame.MAX_CONTROL_PAYLOAD)
                {
                    throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed ["
                            + WebSocketFrame.MAX_CONTROL_PAYLOAD + "]");
                }
                break;
        }
    }

    /**
     * Copy the bytes from one buffer to the other, demasking the content if necessary.
     * 
     * @param src
     *            the source {@link ByteBuffer}
     * @param dest
     *            the destination {@link ByteBuffer}
     * @param length
     *            the length of bytes to worry about
     * @return the number of bytes copied
     */
    protected int copyBuffer(ByteBuffer src, ByteBuffer dest, int length)
    {
        int amt = Math.min(length,src.remaining());
        if (frame.isMasked())
        {
            // Demask the content 1 byte at a time
            // FIXME: on partially parsed frames this needs an offset from prior parse
            byte mask[] = frame.getMask();
            for (int i = 0; i < amt; i++)
            {
                dest.put((byte)(src.get() ^ mask[i % 4]));
            }
        }
        else
        {
            // Copy the content as-is
            // TODO: Look into having a BufferUtil.put(from,to,len) method
            byte b[] = new byte[amt];
            src.get(b,0,amt);
            dest.put(b,0,amt);
        }
        return amt;
    }

    public IncomingFrames getIncomingFramesHandler()
    {
        return incomingFramesHandler;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    protected void notifyFrame(final WebSocketFrame f)
    {
        LOG.debug("Notify Frame: {}",f);
        if (incomingFramesHandler == null)
        {
            return;
        }
        try
        {
            incomingFramesHandler.incoming(f);
        }
        catch (WebSocketException e)
        {
            notifyWebSocketException(e);
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            notifyWebSocketException(new WebSocketException(t));
        }
    }

    protected void notifyWebSocketException(WebSocketException e)
    {
        LOG.debug(e);
        if (incomingFramesHandler == null)
        {
            return;
        }
        incomingFramesHandler.incoming(e);
    }

    public void parse(ByteBuffer buffer)
    {
        if (buffer.remaining() <= 0)
        {
            return;
        }
        try
        {
            LOG.debug("Parsing {} bytes",buffer.remaining());

            // parse through all the frames in the buffer
            while (parseFrame(buffer))
            {
                LOG.debug("Parsed Frame: " + frame);
                notifyFrame(frame);
            }

        }
        catch (WebSocketException e)
        {
            notifyWebSocketException(e);
        }
        catch (Throwable t)
        {
            notifyWebSocketException(new WebSocketException(t));
        }
        finally
        {
            // Be sure to consume after exceptions
            buffer.position(buffer.limit());
        }
    }

    /**
     * Parse the base framing protocol buffer.
     * <p>
     * Note the first byte (fin,rsv1,rsv2,rsv3,opcode) are parsed by the {@link Parser#parse(ByteBuffer)} method
     * <p>
     * Not overridable
     * 
     * @param buffer
     *            the buffer to parse from.
     * @return true if done parsing base framing protocol and ready for parsing of the payload. false if incomplete parsing of base framing protocol.
     */
    private boolean parseFrame(ByteBuffer buffer)
    {
        if (buffer.remaining() <= 0)
        {
            return false;
        }

        LOG.debug("Parsing {} bytes",buffer.remaining());
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case START:
                {
                    if ((frame != null) && (frame.isFin()))
                    {
                        frame.reset();
                    }

                    state = State.FINOP;
                    break;
                }
                case FINOP:
                {
                    // peek at byte
                    byte b = buffer.get();
                    boolean fin = ((b & 0x80) != 0);
                    boolean rsv1 = ((b & 0x40) != 0);
                    boolean rsv2 = ((b & 0x20) != 0);
                    boolean rsv3 = ((b & 0x10) != 0);
                    byte opc = (byte)(b & 0x0F);
                    OpCode opcode = OpCode.from(opc);

                    if (opcode == null)
                    {
                        throw new WebSocketException("Unknown opcode: " + opc);
                    }

                    LOG.debug("OpCode {}, fin={}",opcode.name(),fin);

                    if (opcode.isControlFrame() && !fin)
                    {
                        throw new ProtocolException("Fragmented Control Frame [" + opcode.name() + "]");
                    }

                    if (opcode == OpCode.CONTINUATION)
                    {
                        if (frame == null)
                        {
                            throw new ProtocolException("Fragment continuation frame without prior !FIN");
                        }
                        // Be careful to use the original opcode
                        opcode = frame.getOpCode();
                    }

                    // base framing flags
                    frame = new WebSocketFrame();
                    frame.setFin(fin);
                    frame.setRsv1(rsv1);
                    frame.setRsv2(rsv2);
                    frame.setRsv3(rsv3);
                    frame.setOpCode(opcode);

                    state = State.PAYLOAD_LEN;
                    break;
                }
                case PAYLOAD_LEN:
                {
                    byte b = buffer.get();
                    frame.setMasked((b & 0x80) != 0);
                    payloadLength = (byte)(0x7F & b);

                    if (payloadLength == 127) // 0x7F
                    {
                        // length 8 bytes (extended payload length)
                        payloadLength = 0;
                        state = State.PAYLOAD_LEN_BYTES;
                        cursor = 8;
                        break; // continue onto next state
                    }
                    else if (payloadLength == 126) // 0x7E
                    {
                        // length 2 bytes (extended payload length)
                        payloadLength = 0;
                        state = State.PAYLOAD_LEN_BYTES;
                        cursor = 2;
                        break; // continue onto next state
                    }

                    assertSanePayloadLength(payloadLength);
                    if (frame.isMasked())
                    {
                        state = State.MASK;
                    }
                    else
                    {
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
                        }

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
                        assertSanePayloadLength(payloadLength);
                        if (frame.isMasked())
                        {
                            state = State.MASK;
                        }
                        else
                        {
                            // special case for empty payloads (no more bytes left in buffer)
                            if (payloadLength == 0)
                            {
                                state = State.START;
                                return true;
                            }

                            state = State.PAYLOAD;
                        }
                    }
                    break;
                }
                case MASK:
                {
                    byte m[] = new byte[4];
                    frame.setMask(m);
                    if (buffer.remaining() >= 4)
                    {
                        buffer.get(m,0,4);
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
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
                    --cursor;
                    frame.getMask()[cursor] = b;
                    if (cursor == 0)
                    {
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
                        }

                        state = State.PAYLOAD;
                    }
                    break;
                }
                case PAYLOAD:
                {
                    if (parsePayload(buffer))
                    {
                        // special check for close
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            new CloseInfo(frame);
                        }
                        state = State.START;
                        // we have a frame!
                        return true;
                    }
                    break;
                }
            }
        }

        return false;
    }

    /**
     * Implementation specific parsing of a payload
     * 
     * @param buffer
     *            the payload buffer
     * @return true if payload is done reading, false if incomplete
     */
    public boolean parsePayload(ByteBuffer buffer)
    {
        if (payloadLength == 0)
        {
            return true;
        }

        while (buffer.hasRemaining())
        {
            if (payload == null)
            {
                getPolicy().assertValidPayloadLength(payloadLength);
                frame.assertValid();
                payload = ByteBuffer.allocate(payloadLength);
            }

            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
                BufferUtil.flipToFlush(payload,0);
                frame.setPayload(payload);
                this.payload = null;
                return true;
            }
        }
        return false;
    }

    public void setIncomingFramesHandler(IncomingFrames incoming)
    {
        this.incomingFramesHandler = incoming;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Parser[");
        if (incomingFramesHandler == null)
        {
            builder.append("NO_HANDLER");
        }
        else
        {
            builder.append(incomingFramesHandler.getClass().getSimpleName());
        }
        builder.append(",s=");
        builder.append(state);
        builder.append(",c=");
        builder.append(cursor);
        builder.append(",len=");
        builder.append(payloadLength);
        builder.append(",f=");
        builder.append(frame);
        builder.append("]");
        return builder.toString();
    }
}
