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

package org.eclipse.jetty.quic.common.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FrameStreamTest
{
    @Test
    public void testInOrderOfferNotifiesInOrder()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int length1 = 5;
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1))));

        assertEquals(1, output.size());
        assertEquals(length1, stream.offset());

        int length2 = 7;
        stream.offer(new CryptoFrame(length1, RetainableByteBuffer.wrap(ByteBuffer.allocate(length2))));

        assertEquals(2, output.size());
        assertEquals(length1 + length2, stream.offset());
    }

    @Test
    public void testOutOfOrderOfferNotifiesInOrder()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int length1 = 5;
        int length2 = 7;
        stream.offer(new CryptoFrame(length1, RetainableByteBuffer.wrap(ByteBuffer.allocate(length2))));

        assertEquals(0, output.size());

        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1))));

        assertEquals(2, output.size());
        assertEquals(length1 + length2, stream.offset());
    }

    @Test
    public void testDuplicateOfferIsDiscarded()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int length1 = 5;
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1))));

        assertEquals(1, output.size());
        assertEquals(length1, stream.offset());

        // Duplicate offer.
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1))));
        // Nothing changed.
        assertEquals(1, output.size());
        assertEquals(length1, stream.offset());

        // Make sure a subsequent offer is notified.
        int length2 = 7;
        stream.offer(new CryptoFrame(length1, RetainableByteBuffer.wrap(ByteBuffer.allocate(length2))));

        assertEquals(2, output.size());
        assertEquals(length1 + length2, stream.offset());
    }

    @Test
    public void testOfferSmallerThanGap()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int length1 = 5;
        int length2 = 7;
        stream.offer(new CryptoFrame(length1, RetainableByteBuffer.wrap(ByteBuffer.allocate(length2))));

        assertEquals(0, output.size());

        // Offer a frame smaller than the gap.
        int delta = 1;
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1 - delta))));

        assertEquals(1, output.size());
        assertEquals(length1 - delta, stream.offset());

        // Complete the gap.
        stream.offer(new CryptoFrame(length1 - delta, RetainableByteBuffer.wrap(ByteBuffer.allocate(delta))));

        assertEquals(3, output.size());
        assertEquals(length1 + length2, stream.offset());
    }

    @Test
    public void testOfferGapThatOverlapsPastLength()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int length1 = 5;
        int length2 = 7;
        stream.offer(new CryptoFrame(length1, RetainableByteBuffer.wrap(ByteBuffer.allocate(length2))));

        assertEquals(0, output.size());

        // Offer a frame that overlaps the gap.
        int delta = 3;
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1 + delta))));

        assertEquals(2, output.size());
        assertEquals(length1 + length2, stream.offset());

        CryptoFrame frame1 = (CryptoFrame)output.getFirst();
        assertEquals(0, frame1.offset());
        assertEquals(length1 + delta, frame1.length());

        CryptoFrame frame2 = (CryptoFrame)output.getLast();
        assertEquals(length1 + delta, frame2.offset());
        assertEquals(length2 - delta, frame2.length());
    }

    @Test
    public void testEmptyInitialOfferIsNotifiedThenEmptyOfferIsAlsoNotified()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(0))));

        assertEquals(1, output.size());
        assertEquals(0, stream.offset());

        // A second empty initial offer is also notified.
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(0))));

        assertEquals(2, output.size());
        assertEquals(0, stream.offset());
    }

    @Test
    public void testResetFrameReceivedWithNoData()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        stream.offer(new ResetFrame(0, 0, 0));
        assertEquals(1, output.size());

        // Another ResetFrame is discarded.
        stream.offer(new ResetFrame(0, 0, 0));
        assertEquals(1, output.size());

        // An empty data frame is discarded.
        stream.offer(new StreamFrame(0, RetainableByteBuffer.EMPTY, 0, true, true, false));
        assertEquals(1, output.size());
        stream.offer(new StreamFrame(0, RetainableByteBuffer.EMPTY, 0, true, true, true));
        assertEquals(1, output.size());
    }

    @Test
    public void testResetFrameReceivedAfterLastData()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int finalSize = 32;
        stream.offer(new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(finalSize)), 0, true, true, true));
        assertEquals(1, output.size());

        // A reset with same finalSize is discarded.
        stream.offer(new ResetFrame(0, 0, finalSize));
        assertEquals(1, output.size());

        // A reset with smaller finalSize throws.
        QuicException failure = assertThrows(QuicException.class, () -> stream.offer(new ResetFrame(0, 0, finalSize - 1)));
        assertSame(ErrorCode.FINAL_SIZE_ERROR, failure.getErrorCode());

        // A reset with larger finalSize throws.
        failure = assertThrows(QuicException.class, () -> stream.offer(new ResetFrame(0, 0, finalSize + 1)));
        assertSame(ErrorCode.FINAL_SIZE_ERROR, failure.getErrorCode());
    }

    @Test
    public void testLastDataAfterLastData()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        int finalSize = 32;
        StreamFrame last = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(finalSize)), 0, true, true, true);
        stream.offer(last);
        assertEquals(1, output.size());

        StreamFrame afterLast = new StreamFrame(last.streamId(), RetainableByteBuffer.wrap(ByteBuffer.allocate(1)), stream.offset(), true, true, true);
        QuicException failure = assertThrows(QuicException.class, () -> stream.offer(afterLast));
        assertSame(ErrorCode.FINAL_SIZE_ERROR, failure.getErrorCode());
    }

    @Test
    public void testResetFrameReceivedInOrderIsNotified()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        stream.offer(new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), 0, true, true, false));
        assertEquals(1, output.size());

        long finalSize = stream.offset();
        stream.offer(new ResetFrame(0, 0, finalSize));
        assertEquals(2, output.size());

        assertEquals(finalSize, stream.offset());
        assertInstanceOf(ResetFrame.class, output.getLast());
    }

    @Test
    public void testResetFrameReceivedOutOfOrderIsNotified()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream(output::add);

        StreamFrame data1 = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), 0, true, true, false);
        stream.offer(data1);
        assertEquals(1, output.size());

        StreamFrame data2 = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(16)), stream.offset(), true, true, false);

        stream.offer(new ResetFrame(0, 0, stream.offset() + data2.length()));
        assertEquals(1, output.size());

        stream.offer(data2);
        assertEquals(3, output.size());

        assertEquals(data1.length() + data2.length(), stream.offset());
        assertInstanceOf(ResetFrame.class, output.getLast());
    }
}
