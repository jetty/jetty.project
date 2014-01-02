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

public abstract class ControlFrameGenerator
{
    private final ByteBufferPool bufferPool;

    protected ControlFrameGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    protected ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public abstract ByteBuffer generate(ControlFrame frame);

    protected void generateControlFrameHeader(ControlFrame frame, int frameLength, ByteBuffer buffer)
    {
        buffer.putShort((short)(0x8000 + frame.getVersion()));
        buffer.putShort(frame.getType().getCode());
        int flagsAndLength = frame.getFlags();
        flagsAndLength <<= 24;
        flagsAndLength += frameLength;
        buffer.putInt(flagsAndLength);
    }
}
