//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.generator;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class GoAwayGenerator extends FrameGenerator
{
    private final boolean useDirectByteBuffers;

    public GoAwayGenerator(RetainableByteBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        super(bufferPool);
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public int generate(RetainableByteBufferPool.Accumulator accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        GoAwayFrame goAwayFrame = (GoAwayFrame)frame;
        return generateGoAwayFrame(accumulator, goAwayFrame);
    }

    private int generateGoAwayFrame(RetainableByteBufferPool.Accumulator accumulator, GoAwayFrame frame)
    {
        long lastId = frame.getLastId();
        int lastIdLength = VarLenInt.length(lastId);
        int length = VarLenInt.length(FrameType.GOAWAY.type()) + VarLenInt.length(lastIdLength) + lastIdLength;
        RetainableByteBuffer buffer = getRetainableByteBufferPool().acquire(length, useDirectByteBuffers);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, FrameType.GOAWAY.type());
        VarLenInt.encode(byteBuffer, lastIdLength);
        VarLenInt.encode(byteBuffer, lastId);
        byteBuffer.flip();
        accumulator.append(buffer);
        return length;
    }
}
