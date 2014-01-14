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

import org.eclipse.jetty.spdy.api.StreamStatus;

public class RstStreamFrame extends ControlFrame
{
    private final int streamId;
    private final int statusCode;

    public RstStreamFrame(short version, int streamId, int statusCode)
    {
        super(version, ControlFrameType.RST_STREAM, (byte)0);
        this.streamId = streamId;
        this.statusCode = statusCode;
    }
    
    public int getStreamId()
    {
        return streamId;
    }
    
    public int getStatusCode()
    {
        return statusCode;
    }
    
    @Override
    public String toString()
    {
        StreamStatus streamStatus = StreamStatus.from(getVersion(), getStatusCode());
        return String.format("%s stream=%d status=%s", super.toString(), getStreamId(), streamStatus == null ? getStatusCode() : streamStatus);
    }
}
