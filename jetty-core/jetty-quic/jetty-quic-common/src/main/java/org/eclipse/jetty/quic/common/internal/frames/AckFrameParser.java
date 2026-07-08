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

package org.eclipse.jetty.quic.common.internal.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

/// A parser for QUIC ACK frames.
///
/// NOTE: ACK frames are not used in QUIC-STREAMS, so it is
/// guaranteed that the buffer contains all the ACK frame bytes.
public class AckFrameParser implements FrameParser
{
    @Override
    public Frame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        long type = VarLenInt.decodeLong(byteBuffer);
        long largestAcknowledged = VarLenInt.decodeLong(byteBuffer);
        long ackDelay = VarLenInt.decodeLong(byteBuffer);
        long rangeCount = VarLenInt.decodeLong(byteBuffer);
        long firstRangeLength = VarLenInt.decodeLong(byteBuffer);
        long firstInRange = largestAcknowledged;
        long lastInRange = firstInRange - firstRangeLength;
        // RFC-9000[19.3.1]: packet numbers cannot be negative.
        if (lastInRange < 0)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_ack_frame", type);
        // Each range is at least 2 bytes.
        if (rangeCount > buffer.size() / 2)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_ack_frame", type);
        List<AckFrame.AckRange> ranges = new ArrayList<>();
        for (long i = 0; i < rangeCount; ++i)
        {
            long rangeGap = VarLenInt.decodeLong(byteBuffer);
            firstInRange = (lastInRange - 1) - (rangeGap + 1);
            if (firstInRange < 0)
                throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_ack_frame", type);
            long rangeLength = VarLenInt.decodeLong(byteBuffer);
            lastInRange = firstInRange - rangeLength;
            if (lastInRange < 0)
                throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_ack_frame", type);
            ranges.add(new AckFrame.AckRange(rangeGap, rangeLength));
        }
        // Parse but discard ECN information.
        if (type == 0x03)
        {
            // ECT0.
            VarLenInt.decodeLong(byteBuffer);
            // ECT1.
            VarLenInt.decodeLong(byteBuffer);
            // ECN-CE Count.
            VarLenInt.decodeLong(byteBuffer);
        }
        return new AckFrame(largestAcknowledged, ackDelay, firstRangeLength, ranges);
    }
}
