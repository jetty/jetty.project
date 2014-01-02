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

import org.eclipse.jetty.spdy.api.DataInfo;

public class DataFrame
{
    public static final int HEADER_LENGTH = 8;

    private final int streamId;
    private final byte flags;
    private final int length;

    public DataFrame(int streamId, byte flags, int length)
    {
        this.streamId = streamId;
        this.flags = flags;
        this.length = length;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public byte getFlags()
    {
        return flags;
    }

    public int getLength()
    {
        return length;
    }

    public boolean isClose()
    {
        return (flags & DataInfo.FLAG_CLOSE) == DataInfo.FLAG_CLOSE;
    }

    @Override
    public String toString()
    {
        return String.format("DATA frame stream=%d length=%d close=%b", getStreamId(), getLength(), isClose());
    }
}
