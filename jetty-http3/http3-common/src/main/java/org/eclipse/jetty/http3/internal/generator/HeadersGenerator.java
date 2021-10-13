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
            // Reserve initial bytes for the frame header bytes.
            int frameTypeLength = VarLenInt.length(FrameType.HEADERS.type());
            int maxHeaderLength = frameTypeLength + VarLenInt.MAX_LENGTH;
            ByteBuffer buffer = lease.acquire(maxHeaderLength + maxLength, useDirectByteBuffers);
            buffer.position(maxHeaderLength);
            // Encode after the maxHeaderLength.
            encoder.encode(buffer, streamId, frame.getMetaData());
            buffer.flip();
            buffer.position(maxHeaderLength);
            int dataLength = buffer.remaining();
            int headerLength = frameTypeLength + VarLenInt.length(dataLength);
            int position = buffer.position() - headerLength;
            buffer.position(position);
            VarLenInt.encode(buffer, FrameType.HEADERS.type());
            VarLenInt.encode(buffer, dataLength);
            buffer.position(position);
            lease.append(buffer, true);
            return headerLength + dataLength;
        }
        catch (QpackException e)
        {
            // TODO
            e.printStackTrace();
            return 0;
        }
    }
}
