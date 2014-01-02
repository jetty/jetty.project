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
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.NoOpFrame;
import org.eclipse.jetty.util.BufferUtil;

public class NoOpGenerator extends ControlFrameGenerator
{
    public NoOpGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        NoOpFrame noOp = (NoOpFrame)frame;

        int frameBodyLength = 0;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, Generator.useDirectBuffers);
        BufferUtil.clearToFill(buffer);
        generateControlFrameHeader(noOp, frameBodyLength, buffer);

        buffer.flip();
        return buffer;
    }
}
