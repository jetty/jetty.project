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

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.ByteBufferPool;

public class HeadersGenerator extends FrameGenerator
{
    private final QpackEncoder encoder;
    private final int maxLength;
    private final boolean useDirectByteBuffers;

    public HeadersGenerator(QpackEncoder encoder, int maxLength, boolean useDirectByteBuffers)
    {
        this.encoder = encoder;
        this.maxLength = maxLength;
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, long streamId, Frame frame)
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeadersFrame(lease, streamId, headersFrame);
    }

    private int generateHeadersFrame(ByteBufferPool.Lease lease, long streamId, HeadersFrame frame)
    {
        try
        {
            ByteBuffer buffer = lease.acquire(maxLength, useDirectByteBuffers);
            encoder.encode(buffer, streamId, frame.getMetaData());
            buffer.flip();
            int length = buffer.remaining();
            int capacity = VarLenInt.length(FrameType.HEADERS.type()) + VarLenInt.length(length);
            ByteBuffer header = ByteBuffer.allocate(capacity);
            VarLenInt.generate(header, FrameType.HEADERS.type());
            VarLenInt.generate(header, length);
            header.flip();
            lease.append(header, false);
            lease.append(buffer, true);
            return buffer.remaining();
        }
        catch (QpackException e)
        {
            // TODO
            e.printStackTrace();
            return 0;
        }
    }
}
