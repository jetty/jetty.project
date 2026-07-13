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

package org.eclipse.jetty.quic.api.frames;

import java.util.ArrayList;
import java.util.List;

/// The ACK frame defined in
/// [RFC 9000 #19.3](https://datatracker.ietf.org/doc/html/rfc9000#name-ack-frames).
///
/// No support for ECN (Explicit Congestion Notification), since Java cannot
/// retrieve this information from the UDP datagram.
///
/// # Example
///
/// Packet numbers acked:
/// * 70-75
/// * 90-92
/// * 100-110
///
/// Then the `AckFrame` contains:
/// * `largestAcknowledged=110`
/// * `firstRangeLength=10`
/// * `(AckRange(6,2), AckRange(13,5))`
///
/// The ack ranges are calculated backwards:
/// * Acknowledged packets are 100-110; it's 11 packets, but encoded as length=10.
/// * Unacknowledged packets are 93-99; it's 7 packets, but encoded as gap=6.
/// * Acknowledged packets are 90-92; it's 3 packets, but encoded as length=2.
/// * Unacknowledged packets are 76-89; it's 14 packets, but encoded as gap=13.
/// * Acknowledged packets are 70-75; it's 6 packets, but encoded as length=5.
public final class AckFrame extends Frame.Abstract
{
    /// Encodes the acknowledgment delay as specified in RFC-9000 #19.3.
    ///
    /// @param ackDelay the acknowledgment delay in microseconds
    /// @param ackDelayExponent the local acknowledgment delay exponent
    /// @return the encoded acknowledgment delay carried by an [AckFrame]
    public static long encodeAckDelay(long ackDelay, long ackDelayExponent)
    {
        return ackDelay / (1L << ackDelayExponent);
    }

    /// Decodes the acknowledgment delay as specified in RFC-9000 #19.3.
    ///
    /// @param encodedAckDelay the encoded acknowledgment delay
    /// @param ackDelayExponent the remote acknowledgment delay exponent
    /// @return the acknowledgment delay in microseconds
    public static long decodeAckDelay(long encodedAckDelay, long ackDelayExponent)
    {
        return encodedAckDelay * (1L << ackDelayExponent);
    }

    private final long largestAcknowledged;
    private final long encodedAckDelay;
    private final long firstRangeLength;
    private final List<AckRange> ranges;
    private final List<Long> allAcknowledged;

    public AckFrame(long largestAcknowledged, long encodedAckDelay, long firstRangeLength, List<AckRange> ranges)
    {
        super(0x02);
        this.largestAcknowledged = largestAcknowledged;
        this.encodedAckDelay = encodedAckDelay;
        this.firstRangeLength = firstRangeLength;
        this.ranges = ranges;
        this.allAcknowledged = allAcknowledged(this);
    }

    public long largestAcknowledged()
    {
        return largestAcknowledged;
    }

    public long encodedAckDelay()
    {
        return encodedAckDelay;
    }

    public long firstRangeLength()
    {
        return firstRangeLength;
    }

    public List<AckRange> ackRanges()
    {
        return ranges;
    }

    /// Returns an array of packet numbers acknowledged by this frame,
    /// from the largest acknowledged to the smallest acknowledged.
    ///
    /// From the example above, this method returns:
    /// ```
    /// [110, 109, 108, 107, 106, 105, 104, 103, 102, 101, 100,
    /// 92, 91, 90,
    /// 75, 74, 73, 72, 71, 70]
    /// ```
    public List<Long> allAcknowledged()
    {
        return allAcknowledged;
    }

    private static List<Long> allAcknowledged(AckFrame frame)
    {
        List<Long> result = new ArrayList<>();
        long firstInRange = frame.largestAcknowledged();
        long lastInRange = firstInRange - frame.firstRangeLength();
        collect(result, firstInRange, lastInRange);
        for (AckRange ackRange : frame.ackRanges())
        {
            firstInRange = (lastInRange - 1) - (ackRange.gap() + 1);
            lastInRange = firstInRange - ackRange.length();
            collect(result, firstInRange, lastInRange);
        }
        return result;
    }

    private static void collect(List<Long> result, long firstInRange, long lastInRange)
    {
        while (firstInRange >= lastInRange)
        {
            result.add(firstInRange);
            --firstInRange;
        }
    }

    @Override
    public String toString()
    {
        return "%s[%d-%d,%s]".formatted(
            super.toString(),
            largestAcknowledged(),
            firstRangeLength(),
            ackRanges()
        );
    }

    /// The ack range record.
    ///
    /// @param gap the number of unacknowledged packets from the next range
    /// @param length the number of acknowledged packets in this range
    public record AckRange(long gap, long length)
    {
        @Override
        public String toString()
        {
            return "[%d-%d]".formatted(gap(), length());
        }
    }
}
