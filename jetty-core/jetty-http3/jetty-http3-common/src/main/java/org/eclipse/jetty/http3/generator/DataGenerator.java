//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class DataGenerator extends FrameGenerator
{
    private final boolean useDirectByteBuffers;

    public DataGenerator(WritableBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        super(bufferPool);
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public long generate(List<RetainableByteBuffer> accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        DataFrame dataFrame = (DataFrame)frame;
        return generateDataFrame(accumulator, dataFrame);
    }

    private long generateDataFrame(List<RetainableByteBuffer> accumulator, DataFrame frame)
    {
        RetainableByteBuffer data = frame.acquire();
        long dataLength = data.remaining();
        int headerLength = VarLenInt.length(FrameType.DATA.type()) + VarLenInt.length(dataLength);
        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(headerLength, useDirectByteBuffers);
        VarLenInt.encode(header, FrameType.DATA.type());
        VarLenInt.encode(header, dataLength);
        accumulator.add(header);
        accumulator.add(data);
        return headerLength + dataLength;
    }
}
