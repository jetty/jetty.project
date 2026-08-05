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

package org.eclipse.jetty.http2.generator;

import java.util.List;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ResetGenerator extends FrameGenerator
{
    public ResetGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(List<RetainableByteBuffer> accumulator, Frame frame)
    {
        ResetFrame resetFrame = (ResetFrame)frame;
        return generateReset(accumulator, resetFrame.getStreamId(), resetFrame.getError());
    }

    public int generateReset(List<RetainableByteBuffer> accumulator, int streamId, int error)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        RetainableByteBuffer.Mutable buffer = generateHeader(FrameType.RST_STREAM, ResetFrame.RESET_LENGTH, Flags.NONE, streamId);
        buffer.putInt(error);
        accumulator.add(buffer);

        return Frame.HEADER_LENGTH + ResetFrame.RESET_LENGTH;
    }
}
