//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.generator;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.io.ByteBufferPool;

public class DataGenerator extends FrameGenerator
{
    private final boolean useDirectByteBuffers;

    public DataGenerator(boolean useDirectByteBuffers)
    {
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        DataFrame dataFrame = (DataFrame)frame;
        return generateDataFrame(lease, dataFrame);
    }

    private int generateDataFrame(ByteBufferPool.Lease lease, DataFrame frame)
    {
        ByteBuffer data = frame.getByteBuffer();
        int dataLength = data.remaining();
        int headerLength = VarLenInt.length(FrameType.DATA.type()) + VarLenInt.length(dataLength);
        ByteBuffer header = lease.acquire(headerLength, useDirectByteBuffers);
        VarLenInt.encode(header, FrameType.DATA.type());
        VarLenInt.encode(header, dataLength);
        header.flip();
        lease.append(header, true);
        lease.append(data, false);
        return headerLength + dataLength;
    }
}
