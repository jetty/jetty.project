//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.extensions.Extension;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;

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
        PAYLOAD
    }

    private static final Logger LOG = Log.getLogger(Parser.class);
    private final ByteBufferPool bufferPool;

    // State specific
    private State state = State.START;
    private byte firstByte;
    private int cursor;
    private byte[] mask;
    private int payloadLength;
    private ByteBuffer partialPayload;

    
    public Parser(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
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
                        
                        if ( (b & 0x80) != 0 )
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
                        else if (mask!=null)
                        {
                            state = State.MASK;
                        }
                        else if (payloadLength==0)
                        {
                            state = State.START;
                            return newFrame(firstByte,null,null,false);
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
                            if (mask!=null)
                            {
                                state = State.MASK;
                            }
                            else if (payloadLength==0)
                            {
                                state = State.START;
                                return newFrame(firstByte,null,null,false);
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
                            buffer.get(mask,0,4);
                            if (payloadLength==0)
                            {
                                state = State.START;
                                return newFrame(firstByte,null,null,false);
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
                            if (payloadLength==0)
                            {
                                state = State.START;
                                return newFrame(firstByte,mask,null,false);
                            }
                            state = State.PAYLOAD;
                        }
                        break;
                    }

                    case PAYLOAD:
                    {
                        if (partialPayload==null)
                            checkFrameSize(OpCode.getOpCode(firstByte),payloadLength);
                        ParsedFrame frame = parsePayload(buffer);
                        if (frame!=null)
                            state = State.START;
                        return frame;
                    }
                }
            }
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} Parse Error {}",this,BufferUtil.toDetailString(buffer), t);
            
            buffer.position(buffer.limit()); // consume remaining
            
            // let session know
            WebSocketException wse;
            if(t instanceof WebSocketException)
                wse = (WebSocketException) t;
            else
                wse = new WebSocketException(t);
                
            throw wse;
        }
        finally
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} Parse exit", this);
        }
        
        return null;
    }

    protected void checkFrameSize(byte opcode,int payloadLength) throws MessageTooLargeException, ProtocolException
    {
        if (OpCode.isControlFrame(opcode) && payloadLength>Frame.MAX_CONTROL_PAYLOAD)
            throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");
    }
    
    protected ParsedFrame newFrame(byte firstByte, byte[] mask, ByteBuffer payload, boolean releaseable)
    {
        ParsedFrame frame = new ParsedFrame(firstByte, mask, payload, releaseable);
        reset();
        return frame;
    }
    
    public void reset()
    {
        state = State.START;
        firstByte = 0;
        mask = null;
        cursor = 0;
        partialPayload = null;
        payloadLength = -1;
    }
    
    private ParsedFrame parsePayload(ByteBuffer buffer)
    {        
        if (payloadLength == 0)
            return null;

        if (BufferUtil.isEmpty(buffer))
            return null;

        int available = buffer.remaining();
        
        // If we already have a partial payload, so we need to aggregate to it
        if (partialPayload != null)
        {
            int aggregated = partialPayload.remaining();
            int expecting = payloadLength - aggregated;

            if (available == expecting)
            {
                // All the available data is for this frame and completes it
                BufferUtil.append(partialPayload,buffer);
                return newFrame(firstByte,mask,partialPayload,true);
            }
            
            if (available < expecting)
            {
                // All the available data is for this frame, but not enough to complete it
                BufferUtil.append(partialPayload,buffer);
                return null;
            }

            // The buffer contains data for this frame and subsequent frames
            // Copy the just the last part of the frame
            int limit = buffer.limit();
            buffer.limit(buffer.position() + expecting);
            BufferUtil.append(partialPayload,buffer);
            buffer.limit(limit);
            return newFrame(firstByte,mask,partialPayload,true);
        }

        if (available==payloadLength)
        {         
            // All the available data is for this frame and completes it 
            try
            {
                if (buffer.isDirect())
                {
                    // Copy to indirect, as more efficient for demasking etc.
                    ByteBuffer copy = bufferPool.acquire(buffer.remaining(),false);
                    BufferUtil.append(copy,buffer);
                    return newFrame(firstByte,mask,copy,true);
                }

                // Create a frame that shares the underlying buffer
                return newFrame(firstByte,mask,buffer.duplicate(),false);
            }
            finally
            {
                buffer.position(buffer.limit());
            }
        }
        
        if (available<payloadLength)
        {
            // All the available data is for this frame, but not enough to complete it
            // Is there enough space in the buffer for the rest of the payload?
            if (BufferUtil.space(buffer)>=payloadLength)
                // yes, so let's not consume anything for now and the next fill
                // will add to the existing buffer
                return null;

            // No space in the buffer, so we have to copy the partial payload
            partialPayload = bufferPool.acquire(payloadLength,false);
            BufferUtil.append(partialPayload,buffer);
            return null;
        }

        // The buffer contains data for this frame and subsequent frames
        // Copy the just the last part of the frame
        int limit = buffer.limit();
        int end = buffer.position() + payloadLength;
        buffer.limit(end);
        try
        {
            if (buffer.isDirect())
            {
                // Copy to indirect, as more efficient for demasking etc.
                ByteBuffer copy = bufferPool.acquire(buffer.remaining(),false);
                BufferUtil.append(copy,buffer);
                return newFrame(firstByte,mask,copy,true);
            }

            // Create a frame that shares the underlying buffer
            return newFrame(firstByte,mask,buffer.duplicate(),false);
        }
        finally
        {
            buffer.position(end);
            buffer.limit(limit);
        }   
    }
    
    @Override
    public String toString()
    {
        return String.format("Parser@%x[s=%s,c=%d,o=0x%x,m=%s,l=%d]",hashCode(),state,cursor,firstByte,mask==null?"-":TypeUtil.toHexString(mask),payloadLength);
    }    
    
    public class ParsedFrame extends Frame implements Closeable
    {
        final CloseStatus closeStatus;
        final boolean releaseable;
        
        public ParsedFrame(byte firstByte, byte[] mask, ByteBuffer payload, boolean releaseable)
        {
            super(firstByte,mask,payload);
            demask();
            this.releaseable = releaseable;
            if (getOpCode()==OpCode.CLOSE)
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
        
        public CloseStatus getCloseStatus()
        {
            return closeStatus;
        }

        public boolean isReleaseable()
        {
            return releaseable;
        }
    }
}
