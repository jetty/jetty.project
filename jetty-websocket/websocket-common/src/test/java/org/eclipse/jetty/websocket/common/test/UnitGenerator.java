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

package org.eclipse.jetty.websocket.common.test;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

/**
 * Convenience Generator.
 */
public class UnitGenerator extends Generator
{
    private static final Logger LOG = Log.getLogger(UnitGenerator.class);

    public static ByteBuffer generate(Frame frame)
    {
        return generate(new Frame[]
        { frame });
    }

    /**
     * Generate All Frames into a single ByteBuffer.
     * <p>
     * This is highly inefficient and is not used in production! (This exists to make testing of the Generator easier)
     * 
     * @param frames
     *            the frames to generate from
     * @return the ByteBuffer representing all of the generated frames provided.
     */
    public static ByteBuffer generate(Frame[] frames)
    {
        Generator generator = new UnitGenerator();

        // Generate into single bytebuffer
        int buflen = 0;
        for (Frame f : frames)
        {
            buflen += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }
        ByteBuffer completeBuf = ByteBuffer.allocate(buflen);
        BufferUtil.clearToFill(completeBuf);

        // Generate frames
        for (Frame f : frames)
        {
            generator.generateWholeFrame(f,completeBuf);
        }

        BufferUtil.flipToFlush(completeBuf,0);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("generate({} frames) - {}",frames.length,BufferUtil.toDetailString(completeBuf));
        }
        return completeBuf;
    }

    /**
     * Generate a single giant buffer of all provided frames Not appropriate for production code, but useful for testing.
     * @param frames the list of frames to generate from
     * @return the bytebuffer representing all of the generated frames
     */
    public static ByteBuffer generate(List<WebSocketFrame> frames)
    {
        // Create non-symmetrical mask (helps show mask bytes order issues)
        byte[] MASK =
        { 0x11, 0x22, 0x33, 0x44 };

        // the generator
        Generator generator = new UnitGenerator();

        // Generate into single bytebuffer
        int buflen = 0;
        for (Frame f : frames)
        {
            buflen += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }
        ByteBuffer completeBuf = ByteBuffer.allocate(buflen);
        BufferUtil.clearToFill(completeBuf);

        // Generate frames
        for (WebSocketFrame f : frames)
        {
            f.setMask(MASK); // make sure we have the test mask set
            BufferUtil.put(generator.generateHeaderBytes(f),completeBuf);
            ByteBuffer window = f.getPayload();
            if (BufferUtil.hasContent(window))
            {
                BufferUtil.put(window,completeBuf);
            }
        }

        BufferUtil.flipToFlush(completeBuf,0);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("generate({} frames) - {}",frames.size(),BufferUtil.toDetailString(completeBuf));
        }
        return completeBuf;
    }

    public UnitGenerator()
    {
        super(WebSocketPolicy.newServerPolicy(),new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged()));
    }
    
    public UnitGenerator(ByteBufferPool bufferPool)
    {
        super(WebSocketPolicy.newServerPolicy(),bufferPool);
    }
}
