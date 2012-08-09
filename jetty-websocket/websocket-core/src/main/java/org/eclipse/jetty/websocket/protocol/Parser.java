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
import org.eclipse.jetty.websocket.io.payload.BinaryValidator;
import org.eclipse.jetty.websocket.io.payload.CloseReasonValidator;
import org.eclipse.jetty.websocket.io.payload.DeMaskProcessor;
import org.eclipse.jetty.websocket.io.payload.PayloadProcessor;
import org.eclipse.jetty.websocket.io.payload.UTF8Validator;

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

    private static final Logger LOG_FRAMES = Log.getLogger("org.eclipse.jetty.websocket.io.Frames");

    // State specific
    private State state = State.START;
    private int cursor = 0;
    // Frame
    private WebSocketFrame frame;
    private WebSocketFrame priorDataFrame;
    private byte lastDataOpcode;
    // payload specific
    private ByteBuffer payload;
    private int payloadLength;
    private PayloadProcessor maskProcessor = new DeMaskProcessor();
    private PayloadProcessor strictnessProcessor;

    /** Is there an extension using RSV1 */
    private boolean rsv1InUse = false;
    /** Is there an extension using RSV2 */
    private boolean rsv2InUse = false;
    /** Is there an extension using RSV3 */
    private boolean rsv3InUse = false;

    private static final Logger LOG = Log.getLogger(Parser.class);
    private IncomingFrames incomingFramesHandler;
    private WebSocketPolicy policy;

    public Parser(WebSocketPolicy wspolicy)
    {
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
            case OpCode.CLOSE:
                if (len == 1)
                {
                    throw new ProtocolException("Invalid close frame payload length, [" + payloadLength + "]");
                }
                // fall thru
            case OpCode.PING:
            case OpCode.PONG:
                if (len > WebSocketFrame.MAX_CONTROL_PAYLOAD)
                {
                    throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed ["
                            + WebSocketFrame.MAX_CONTROL_PAYLOAD + "]");
                }
                break;
        }
    }

    public IncomingFrames getIncomingFramesHandler()
    {
        return incomingFramesHandler;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public boolean isRsv1InUse()
    {
        return rsv1InUse;
    }

    public boolean isRsv2InUse()
    {
        return rsv2InUse;
    }

    public boolean isRsv3InUse()
    {
        return rsv3InUse;
    }

    protected void notifyFrame(final WebSocketFrame f)
    {
        if (LOG_FRAMES.isDebugEnabled())
        {
            LOG_FRAMES.debug("{} Read Frame: {}",policy.getBehavior(),f);
        }
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} Notify {}",policy.getBehavior(),incomingFramesHandler);
        }
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

    public synchronized void parse(ByteBuffer buffer)
    {
        if (buffer.remaining() <= 0)
        {
            return;
        }
        try
        {
            // parse through all the frames in the buffer
            while (parseFrame(buffer))
            {
                LOG.debug("{} Parsed Frame: {}",policy.getBehavior(),frame);
                notifyFrame(frame);
                if (frame.isDataFrame() && frame.isFin())
                {
                    priorDataFrame = null;
                }
                else
                {
                    priorDataFrame = frame;
                }
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

        LOG.debug("{} Parsing {} bytes",policy.getBehavior(),buffer.remaining());
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
                    byte opcode = opc;

                    if (!OpCode.isKnown(opcode))
                    {
                        throw new ProtocolException("Unknown opcode: " + opc);
                    }

                    LOG.debug("OpCode {}, fin={}",OpCode.name(opcode),fin);

                    /*
                     * RFC 6455 Section 5.2
                     * 
                     * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the
                     * negotiated extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
                     */
                    if (!rsv1InUse && rsv1)
                    {
                        throw new ProtocolException("RSV1 not allowed to be set");
                    }

                    if (!rsv2InUse && rsv2)
                    {
                        throw new ProtocolException("RSV2 not allowed to be set");
                    }

                    if (!rsv3InUse && rsv3)
                    {
                        throw new ProtocolException("RSV3 not allowed to be set");
                    }

                    boolean isContinuation = false;

                    switch (opcode)
                    {
                        case OpCode.TEXT:
                            strictnessProcessor = new UTF8Validator();
                            break;
                        case OpCode.CLOSE:
                            strictnessProcessor = new CloseReasonValidator();
                            break;
                        default:
                            strictnessProcessor = BinaryValidator.INSTANCE;
                            break;
                    }

                    if (OpCode.isControlFrame(opcode))
                    {
                        // control frame validation
                        if (!fin)
                        {
                            throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(opcode) + "]");
                        }
                    }
                    else if (opcode == OpCode.CONTINUATION)
                    {
                        isContinuation = true;
                        // continuation validation
                        if (priorDataFrame == null)
                        {
                            throw new ProtocolException("CONTINUATION frame without prior !FIN");
                        }
                        // Be careful to use the original opcode
                        opcode = lastDataOpcode;
                    }
                    else if (OpCode.isDataFrame(opcode))
                    {
                        // data validation
                        if ((priorDataFrame != null) && (!priorDataFrame.isFin()))
                        {
                            throw new ProtocolException("Unexpected " + OpCode.name(opcode) + " frame, was expecting CONTINUATION");
                        }
                    }

                    // base framing flags
                    frame = new WebSocketFrame();
                    frame.setFin(fin);
                    frame.setRsv1(rsv1);
                    frame.setRsv2(rsv2);
                    frame.setRsv3(rsv3);
                    frame.setOpCode(opcode);
                    frame.setContinuation(isContinuation);

                    if (frame.isDataFrame())
                    {
                        lastDataOpcode = opcode;
                    }

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

                        maskProcessor.reset(frame);
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

                            maskProcessor.reset(frame);
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

                        maskProcessor.reset(frame);
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
                    frame.getMask()[4 - cursor] = b;
                    --cursor;
                    if (cursor == 0)
                    {
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
                        }

                        maskProcessor.reset(frame);
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
    private boolean parsePayload(ByteBuffer buffer)
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
                BufferUtil.clearToFill(payload);
            }

            // Create a small window of the incoming buffer to work with.
            // this should only show the payload itself, and not any more
            // bytes that could belong to the start of the next frame.
            ByteBuffer window = buffer.slice();
            int bytesExpected = payloadLength - payload.position();
            int bytesAvailable = buffer.remaining();
            int windowBytes = Math.min(bytesAvailable,bytesExpected);
            window.limit(window.position() + windowBytes);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Window: {}",BufferUtil.toDetailString(window));
            }

            maskProcessor.process(window);
            strictnessProcessor.process(window);
            int len = BufferUtil.put(window,payload);

            buffer.position(buffer.position() + len); // update incoming buffer position

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

    public void setRsv1InUse(boolean rsv1InUse)
    {
        this.rsv1InUse = rsv1InUse;
    }

    public void setRsv2InUse(boolean rsv2InUse)
    {
        this.rsv2InUse = rsv2InUse;
    }

    public void setRsv3InUse(boolean rsv3InUse)
    {
        this.rsv3InUse = rsv3InUse;
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
