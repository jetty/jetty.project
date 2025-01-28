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

public class AckFrame extends Frame
{
    private final long ackNumber;
    private final long ackDelay;
    private final List<Integer> ranges;
    private final long ect0Count;
    private final long ect1Count;
    private final long ceCount;

    public AckFrame(long ackNumber, long ackDelay, List<Integer> ranges)
    {
        this(0x02, ackNumber, ackDelay, ranges, 0, 0, 0);
    }

    public AckFrame(long ackNumber, long ackDelay, List<Integer> ranges, int ect0Count, int ect1Count, int ceCount)
    {
        this(0x03, ackNumber, ackDelay, ranges, ect0Count, ect1Count, ceCount);
    }

    private AckFrame(int type, long ackNumber, long ackDelay, List<Integer> ranges, int ect0Count, int ect1Count, int ceCount)
    {
        super(type);
        this.ackNumber = ackNumber;
        this.ackDelay = ackDelay;
        if (ranges.size() < 1)
            throw new IllegalArgumentException("invalid_range_list");
        this.ranges = ranges;
        this.ect0Count = ect0Count;
        this.ect1Count = ect1Count;
        this.ceCount = ceCount;
    }

    public long getAckNumber()
    {
        return ackNumber;
    }

    public long getAckDelay()
    {
        return ackDelay;
    }

    public List<Integer> getRanges()
    {
        return ranges;
    }

    public long getECT0Count()
    {
        return ect0Count;
    }

    public long getECT1Count()
    {
        return ect1Count;
    }

    public long getCECount()
    {
        return ceCount;
    }
}
