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
import java.util.List;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/**
 * Convenience Generator.
 */
public class UnitGenerator extends Generator
{
    public static ByteBuffer generate(List<WebSocketFrame> frames)
    {
        // Create non-symmetrical mask (shows mask bytes order issues)
        byte[] MASK =
            { 0x11, 0x22, 0x33, 0x44 };

        // the generator
        Generator generator = new UnitGenerator();

        // Generate into single bytebuffer
        int buflen = 0;
        for (WebSocketFrame f : frames)
        {
            buflen += f.getPayloadLength() + Generator.OVERHEAD;
        }
        ByteBuffer completeBuf = ByteBuffer.allocate(buflen);
        BufferUtil.clearToFill(completeBuf);

        // Generate frames
        for (WebSocketFrame f : frames)
        {
            f.setMask(MASK); // make sure we have mask set
            ByteBuffer slice = f.getPayload().slice();
            BufferUtil.put(generator.generate(f),completeBuf);
            f.setPayload(slice);
        }

        BufferUtil.flipToFlush(completeBuf,0);
        return completeBuf;
    }

    public UnitGenerator()
    {
        super(WebSocketPolicy.newServerPolicy(),new StandardByteBufferPool());
    }
}
