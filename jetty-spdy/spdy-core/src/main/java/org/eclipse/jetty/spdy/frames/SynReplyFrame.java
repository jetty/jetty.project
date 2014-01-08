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

import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.util.Fields;

public class SynReplyFrame extends ControlFrame
{
    private final int streamId;
    private final Fields headers;

    public SynReplyFrame(short version, byte flags, int streamId, Fields headers)
    {
        super(version, ControlFrameType.SYN_REPLY, flags);
        this.streamId = streamId;
        this.headers = headers;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public Fields getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return (getFlags() & ReplyInfo.FLAG_CLOSE) == ReplyInfo.FLAG_CLOSE;
    }

    @Override
    public String toString()
    {
        return String.format("%s stream=%d close=%b", super.toString(), getStreamId(), isClose());
    }
}
