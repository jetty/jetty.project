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
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class GoAwayGenerator extends FrameGenerator
{
    public GoAwayGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(List<ReadableBuffer> accumulator, Frame frame)
    {
        GoAwayFrame goAwayFrame = (GoAwayFrame)frame;
        return generateGoAway(accumulator, goAwayFrame.getLastStreamId(), goAwayFrame.getError(), goAwayFrame.getPayload());
    }

    public int generateGoAway(List<ReadableBuffer> accumulator, int lastStreamId, int error, byte[] payload)
    {
        if (lastStreamId < 0)
            lastStreamId = 0;

        // The last streamId + the error code.
        int fixedLength = 4 + 4;

        // Make sure we don't exceed the default frame max length.
        int maxPayloadLength = Frame.DEFAULT_MAX_SIZE - fixedLength;
        int payloadLength = Math.min(payload == null ? 0 : payload.length, maxPayloadLength);

        int length = fixedLength + payloadLength;
        WritableBuffer wb = generateHeader(FrameType.GO_AWAY, length, Flags.NONE, 0);
        wb.putInt(lastStreamId);
        wb.putInt(error);

        if (payload != null)
            wb.put(payload, 0, payloadLength);

        accumulator.add(wb.toReadable());

        return Frame.HEADER_LENGTH + length;
    }
}
