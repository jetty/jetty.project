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

import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.util.Fields;

public class HeadersFrame extends ControlFrame
{
    private final int streamId;
    private final Fields headers;

    public HeadersFrame(short version, byte flags, int streamId, Fields headers)
    {
        super(version, ControlFrameType.HEADERS, flags);
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
        return (getFlags() & HeadersInfo.FLAG_CLOSE) == HeadersInfo.FLAG_CLOSE;
    }

    public boolean isResetCompression()
    {
        return (getFlags() & HeadersInfo.FLAG_RESET_COMPRESSION) == HeadersInfo.FLAG_RESET_COMPRESSION;
    }

    @Override
    public String toString()
    {
        return String.format("%s stream=%d close=%b reset_compression=%b", super.toString(), getStreamId(), isClose(), isResetCompression());
    }
}
