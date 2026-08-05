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

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class HeadersGenerator extends FrameGenerator
{
    private final QpackEncoder encoder;
    private final boolean useDirectByteBuffers;

    public HeadersGenerator(WritableBufferPool bufferPool, QpackEncoder encoder, boolean useDirectByteBuffers)
    {
        super(bufferPool);
        this.encoder = encoder;
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public long generate(List<RetainableByteBuffer> accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeadersFrame(accumulator, streamId, headersFrame, fail);
    }

    private long generateHeadersFrame(List<RetainableByteBuffer> accumulator, long streamId, HeadersFrame frame, Consumer<Throwable> fail)
    {
        // Reserve initial bytes for the frame header bytes.
        int frameTypeLength = VarLenInt.length(FrameType.HEADERS.type());
        int maxHeaderLength = frameTypeLength + VarLenInt.MAX_LENGTH;
        // The capacity of the buffer is larger than maxLength, but we need to enforce at most maxLength.
        int maxLength = encoder.getMaxHeadersSize();
        // Acquire buffer and immediately add to the accumulator so that it is released if a failure occurs.
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(maxHeaderLength + maxLength, useDirectByteBuffers);
        accumulator.add(buffer);
        try
        {
            // Prepare the buffer for encoding.
            // Start writing and reading from the maxHeaderLength position.
            buffer.writePosition(maxHeaderLength);
            buffer.readPosition(maxHeaderLength);

            // Encode after the maxHeaderLength position.
            encoder.encode(buffer, streamId, frame.getMetaData());
            long dataLength = buffer.remaining();

            // Write the header before the maxHeaderLength position.
            int headerLength = frameTypeLength + VarLenInt.length(dataLength);
            long headerStartPosition = maxHeaderLength - headerLength;
            buffer.writePosition(headerStartPosition);
            VarLenInt.encode(buffer, FrameType.HEADERS.type());
            VarLenInt.encode(buffer, dataLength);
            // Restore the written bytes to include the encoded bytes.
            buffer.writePosition(maxHeaderLength + dataLength);
            // Start reading from the beginning of the header,
            // before the maxHeaderLength position.
            buffer.readPosition(headerStartPosition);
            return headerLength + dataLength;
        }
        catch (QpackException x)
        {
            if (fail != null)
                fail.accept(x);
            return -1;
        }
    }
}
