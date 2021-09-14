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

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.io.ByteBufferPool;

public class DataGenerator extends FrameGenerator
{
    @Override
    public int generate(ByteBufferPool.Lease lease, long streamId, Frame frame)
    {
        DataFrame dataFrame = (DataFrame)frame;
        return generateDataFrame(lease, dataFrame);
    }

    private int generateDataFrame(ByteBufferPool.Lease lease, DataFrame frame)
    {
        ByteBuffer data = frame.getData();
        int dataLength = data.remaining();
        int headerLength = VarLenInt.length(FrameType.DATA.type()) + VarLenInt.length(dataLength);
        ByteBuffer header = ByteBuffer.allocate(headerLength);
        VarLenInt.generate(header, FrameType.DATA.type());
        VarLenInt.generate(header, dataLength);
        header.flip();
        lease.append(header, false);
        lease.append(data, false);
        return headerLength + dataLength;
    }
}
