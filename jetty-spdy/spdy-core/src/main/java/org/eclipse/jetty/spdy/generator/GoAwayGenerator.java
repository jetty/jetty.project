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

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;
import org.eclipse.jetty.util.BufferUtil;

public class GoAwayGenerator extends ControlFrameGenerator
{
    public GoAwayGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        GoAwayFrame goAway = (GoAwayFrame)frame;

        int frameBodyLength = 8;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, Generator.useDirectBuffers);
        BufferUtil.clearToFill(buffer);
        generateControlFrameHeader(goAway, frameBodyLength, buffer);

        buffer.putInt(goAway.getLastStreamId() & 0x7F_FF_FF_FF);
        writeStatusCode(goAway, buffer);

        buffer.flip();
        return buffer;
    }

    private void writeStatusCode(GoAwayFrame goAway, ByteBuffer buffer)
    {
        switch (goAway.getVersion())
        {
            case SPDY.V2:
                break;
            case SPDY.V3:
                buffer.putInt(goAway.getStatusCode());
                break;
            default:
                throw new IllegalStateException();
        }
    }
}
