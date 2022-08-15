//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.frames;

public class PriorityFrame extends StreamFrame
{
    public static final int PRIORITY_LENGTH = 5;

    private final int parentStreamId;
    private final int weight;
    private final boolean exclusive;

    public PriorityFrame(int parentStreamId, int weight, boolean exclusive)
    {
        this(0, parentStreamId, weight, exclusive);
    }

    public PriorityFrame(int streamId, int parentStreamId, int weight, boolean exclusive)
    {
        super(FrameType.PRIORITY, streamId);
        this.parentStreamId = parentStreamId;
        this.weight = weight;
        this.exclusive = exclusive;
    }

    public int getParentStreamId()
    {
        return parentStreamId;
    }

    public int getWeight()
    {
        return weight;
    }

    public boolean isExclusive()
    {
        return exclusive;
    }

    @Override
    public PriorityFrame withStreamId(int streamId)
    {
        return new PriorityFrame(streamId, getParentStreamId(), getWeight(), isExclusive());
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d/#%d{weight=%d,exclusive=%b}", super.toString(), getStreamId(), parentStreamId, weight, exclusive);
    }
}
