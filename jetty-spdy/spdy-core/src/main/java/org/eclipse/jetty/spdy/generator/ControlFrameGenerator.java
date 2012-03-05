/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.ByteBufferPool;
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
