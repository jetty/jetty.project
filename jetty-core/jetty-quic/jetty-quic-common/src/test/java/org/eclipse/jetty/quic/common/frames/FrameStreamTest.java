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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
