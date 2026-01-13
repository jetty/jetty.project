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
        int rangeCount = VarLenInt.decodeInt(byteBuffer);
        long firstRangeLength = VarLenInt.decodeLong(byteBuffer);
        List<AckFrame.AckRange> ranges = new ArrayList<>(rangeCount);
        for (int i = 0; i < rangeCount; ++i)
        {
            long gap = VarLenInt.decodeLong(byteBuffer);
            long length = VarLenInt.decodeLong(byteBuffer);
            ranges.add(new AckFrame.AckRange(gap, length));
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
