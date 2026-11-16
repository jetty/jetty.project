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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

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
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

        int length1 = 5;
        int length2 = 7;
        stream.offer(new CryptoFrame(length1, RetainableByteBuffer.wrap(ByteBuffer.allocate(length2))));

        assertEquals(0, output.size());

        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(length1))));

        assertEquals(2, output.size());
        assertEquals(length1 + length2, stream.offset());
    }

    @Test
    public void testDuplicateOfferIsDiscardedAtCurrentOffset()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

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
    public void testDuplicateOfferIsDiscardedAtLargerOffset()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

        // Offer at a larger offset.
        stream.offer(new CryptoFrame(1, RetainableByteBuffer.wrap(ByteBuffer.allocate(1))));
        assertEquals(1, stream.queueSize());
        assertEquals(0, output.size());
        assertEquals(0, stream.offset());


        // Duplicate offer.
        stream.offer(new CryptoFrame(1, RetainableByteBuffer.wrap(ByteBuffer.allocate(1))));

        // Nothing changed.
        assertEquals(1, stream.queueSize());
        assertEquals(0, output.size());
        assertEquals(0, stream.offset());
    }

    @Test
    public void testOfferSmallerThanGap()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

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
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        byte[] data1 = new byte[5];
        random.nextBytes(data1);
        byte[] data2 = new byte[7];
        random.nextBytes(data2);
        stream.offer(new CryptoFrame(data1.length, RetainableByteBuffer.wrap(ByteBuffer.wrap(data2))));

        assertEquals(0, output.size());

        // Offer a frame that overlaps the gap.
        int delta = 3;
        byte[] data3 = new byte[data1.length + delta];
        System.arraycopy(data1, 0, data3, 0, data1.length);
        stream.offer(new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.wrap(data3))));

        assertEquals(2, output.size());
        assertEquals(data1.length + data2.length, stream.offset());

        CryptoFrame frame1 = (CryptoFrame)output.getFirst();
        assertEquals(0, frame1.offset());
        assertEquals(data1.length + delta, frame1.length());
        try (RetainableByteBuffer buffer = frame1.acquire())
        {
            assertArrayEquals(data3, buffer.getArray());
        }

        CryptoFrame frame2 = (CryptoFrame)output.getLast();
        assertEquals(data1.length + delta, frame2.offset());
        assertEquals(data2.length - delta, frame2.length());
        try (RetainableByteBuffer buffer = frame2.acquire())
        {
            assertArrayEquals(Arrays.copyOfRange(data2, delta, data2.length), buffer.getArray());
        }
    }

    @Test
    public void testEmptyInitialOfferIsNotifiedThenEmptyOfferIsAlsoNotified()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Crypto(256, output::add, EncryptionLevel.INITIAL);

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
        FrameStream stream = new FrameStream.Stream(256, output::add, 0);

        stream.offer(new ResetStreamFrame(0, 0, 0));
        assertEquals(1, output.size());

        // Another ResetFrame is discarded.
        stream.offer(new ResetStreamFrame(0, 0, 0));
        assertEquals(1, output.size());

        // An empty data frame is discarded.
        stream.offer(new StreamFrame(0, RetainableByteBuffer.empty(), 0, true, true, false));
        assertEquals(1, output.size());
        stream.offer(new StreamFrame(0, RetainableByteBuffer.empty(), 0, true, true, true));
        assertEquals(1, output.size());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    public void testResetFrameReceivedWithDeliveredNonLastData(long offset)
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Stream(256, output::add, 0);

        StreamFrame frame = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), offset, true, true, false);
        stream.offer(frame);

        // Offer a final size smaller than what already queued/delivered.
        QuicException failure = assertThrows(QuicException.class, () -> stream.offer(new ResetStreamFrame(0, 0, frame.endOffset() - 1)));
        assertSame(ErrorCode.FINAL_SIZE_ERROR, failure.getErrorCode());
    }

    @Test
    public void testResetFrameReceivedAfterLastData()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Stream(256, output::add, 0);

        int finalSize = 32;
        stream.offer(new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(finalSize)), 0, true, true, true));
        assertEquals(1, output.size());

        // A reset with same finalSize is discarded.
        stream.offer(new ResetStreamFrame(0, 0, finalSize));
        assertEquals(1, output.size());

        // A reset with smaller finalSize throws.
        QuicException failure = assertThrows(QuicException.class, () -> stream.offer(new ResetStreamFrame(0, 0, finalSize - 1)));
        assertSame(ErrorCode.FINAL_SIZE_ERROR, failure.getErrorCode());

        // A reset with larger finalSize throws.
        failure = assertThrows(QuicException.class, () -> stream.offer(new ResetStreamFrame(0, 0, finalSize + 1)));
        assertSame(ErrorCode.FINAL_SIZE_ERROR, failure.getErrorCode());
    }

    @Test
    public void testLastDataAfterLastData()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Stream(256, output::add, 0);

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
        FrameStream stream = new FrameStream.Stream(256, output::add, 0);

        stream.offer(new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), 0, true, true, false));
        assertEquals(1, output.size());

        long finalSize = stream.offset();
        stream.offer(new ResetStreamFrame(0, 0, finalSize));
        assertEquals(2, output.size());

        assertEquals(finalSize, stream.offset());
        assertInstanceOf(ResetStreamFrame.class, output.getLast());
    }

    @Test
    public void testResetFrameReceivedOutOfOrderIsNotified()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Stream(256, output::add, 0);

        StreamFrame data1 = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), 0, true, true, false);
        stream.offer(data1);
        assertEquals(1, output.size());

        StreamFrame data2 = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(16)), stream.offset(), true, true, false);

        stream.offer(new ResetStreamFrame(0, 0, stream.offset() + data2.length()));
        assertEquals(1, output.size());

        stream.offer(data2);
        assertEquals(3, output.size());

        assertEquals(data1.length() + data2.length(), stream.offset());
        assertInstanceOf(ResetStreamFrame.class, output.getLast());
    }

    @Test
    public void testCryptoMaxSize()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Crypto(1, output::add, EncryptionLevel.INITIAL);

        CryptoFrame crypto1 = new CryptoFrame(1, RetainableByteBuffer.wrap(ByteBuffer.allocate(24)));
        stream.offer(crypto1);
        assertEquals(1, stream.queueSize());
        assertEquals(0, output.size());

        long offset2 = crypto1.endOffset() + 1;
        CryptoFrame crypto2 = new CryptoFrame(offset2, RetainableByteBuffer.wrap(ByteBuffer.allocate(48)));
        QuicException failure = assertThrows(QuicException.class, () -> stream.offer(crypto2));

        assertSame(ErrorCode.CRYPTO_BUFFER_EXCEEDED_ERROR, failure.getErrorCode());
    }

    @Test
    public void testStreamMaxSize()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Stream(1, output::add, 0);

        StreamFrame data1 = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), 1, true, true, false);
        stream.offer(data1);
        assertEquals(1, stream.queueSize());
        assertEquals(0, output.size());

        long offset2 = data1.endOffset() + 1;
        StreamFrame data2 = new StreamFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(32)), offset2, true, true, false);
        QuicException failure = assertThrows(QuicException.class, () -> stream.offer(data2));

        assertSame(ErrorCode.PROTOCOL_VIOLATION_ERROR, failure.getErrorCode());
    }

    @Test
    public void testQueueMaxedUnblockingFrameArrives()
    {
        List<Frame> output = new ArrayList<>();
        FrameStream stream = new FrameStream.Crypto(1, output::add, EncryptionLevel.INITIAL);

        int offset1 = 1;
        CryptoFrame crypto1 = new CryptoFrame(offset1, RetainableByteBuffer.wrap(ByteBuffer.allocate(24)));
        stream.offer(crypto1);
        assertEquals(1, stream.queueSize());
        assertEquals(0, output.size());

        CryptoFrame crypto0 = new CryptoFrame(0, RetainableByteBuffer.wrap(ByteBuffer.allocate(offset1)));
        stream.offer(crypto0);

        assertEquals(0, stream.queueSize());
        assertEquals(2, output.size());
    }
}
