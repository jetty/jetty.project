//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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

    /**
     * @return {@code int} of the Parent Stream
     * @deprecated use {@link #getParentStreamId()} instead.
     */
    @Deprecated
    public int getDependentStreamId()
    {
        return getParentStreamId();
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
