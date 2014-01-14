//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.PushSynInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.Fields;

public class SynStreamFrame extends ControlFrame
{
    private final int streamId;
    private final int associatedStreamId;
    private final byte priority;
    private final short slot;
    private final Fields headers;

    public SynStreamFrame(short version, byte flags, int streamId, int associatedStreamId, byte priority, short slot, Fields headers)
    {
        super(version, ControlFrameType.SYN_STREAM, flags);
        this.streamId = streamId;
        this.associatedStreamId = associatedStreamId;
        this.priority = priority;
        this.slot = slot;
        this.headers = headers;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getAssociatedStreamId()
    {
        return associatedStreamId;
    }

    public byte getPriority()
    {
        return priority;
    }

    public short getSlot()
    {
        return slot;
    }

    public Fields getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return (getFlags() & SynInfo.FLAG_CLOSE) == SynInfo.FLAG_CLOSE;
    }

    public boolean isUnidirectional()
    {
        return (getFlags() & PushSynInfo.FLAG_UNIDIRECTIONAL) == PushSynInfo.FLAG_UNIDIRECTIONAL;
    }

    @Override
    public String toString()
    {
        return String.format("%s stream=%d close=%b", super.toString(), getStreamId(), isClose());
    }
}
