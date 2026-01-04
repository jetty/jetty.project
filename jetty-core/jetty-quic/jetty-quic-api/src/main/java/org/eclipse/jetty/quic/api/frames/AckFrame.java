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

import java.util.List;

/// The ACK frame defined in
/// [RFC 9000, 19.3](https://datatracker.ietf.org/doc/html/rfc9000#name-ack-frames).
///
/// No support for ECN (Explicit Congestion Notification).
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
/// * Unacknowledged packets are 93-99; it's 7 packets, but encoded as gap=6.
/// * Acknowledged packets are 90-92; it's 3 packets, but encoded as length=2.
/// * Unacknowledged packets are 76-89; it's 14 packets, but encoded as gap=13.
/// * Acknowledged packets are 70-75; it's 6 packets, but encoded as length=5.
public class AckFrame extends Frame
{
    private final long largestAcknowledged;
    private final long ackDelay;
    private final long firstRangeLength;
    private final List<AckRange> ranges;

    public AckFrame(long largestAcknowledged, long ackDelay, long firstRangeLength, List<AckRange> ranges)
    {
        super(0x02);
        this.largestAcknowledged = largestAcknowledged;
        this.ackDelay = ackDelay;
        this.firstRangeLength = firstRangeLength;
        this.ranges = ranges;
    }

    public long getLargestAcknowledged()
    {
        return largestAcknowledged;
    }

    public long getAckDelay()
    {
        return ackDelay;
    }

    public long getFirstRangeLength()
    {
        return firstRangeLength;
    }

    public List<AckRange> getAckRanges()
    {
        return ranges;
    }

    /// The ack range record.
    ///
    ///
    public record AckRange(long gap, long length)
    {
    }
}
