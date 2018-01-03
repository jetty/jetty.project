//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.generator;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;

/**
 * Extension of the default WebSocket Generator for unit testing purposes
 */
public class UnitGenerator extends Generator
{
    // Client side framing mask
    private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
    private final boolean applyMask;
    
    public UnitGenerator(WebSocketPolicy policy)
    {
        super(policy, new MappedByteBufferPool(), false);
        applyMask = (getBehavior() == WebSocketBehavior.CLIENT);
    }
    
    public UnitGenerator(WebSocketPolicy policy, boolean validating)
    {
        super(policy, new MappedByteBufferPool(), validating);
        applyMask = (getBehavior() == WebSocketBehavior.CLIENT);
    }
    
    public ByteBuffer asBuffer(List<WebSocketFrame> frames)
    {
        int bufferLength = 0;
        for (Frame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
        generate(buffer, frames);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }
    
    public ByteBuffer asBuffer(WebSocketFrame... frames)
    {
        int bufferLength = 0;
        for (Frame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
        generate(buffer, frames);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }
    
    public void generate(ByteBuffer buffer, List<WebSocketFrame> frames)
    {
        // Generate frames
        for (WebSocketFrame f : frames)
        {
            if (applyMask)
                f.setMask(MASK);
            
            generateWholeFrame(f, buffer);
        }
    }
    
    public void generate(ByteBuffer buffer, WebSocketFrame... frames)
    {
        // Generate frames
        for (WebSocketFrame f : frames)
        {
            if (applyMask)
                f.setMask(MASK);
            
            generateWholeFrame(f, buffer);
        }
    }
    
    public ByteBuffer generate(WebSocketFrame frame)
    {
        int bufferLength = frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
        if (applyMask)
            frame.setMask(MASK);
        generateWholeFrame(frame, buffer);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }
    
    public void generate(ByteBuffer buffer, WebSocketFrame frame)
    {
        if (applyMask)
            frame.setMask(MASK);
        generateWholeFrame(frame, buffer);
    }
}
