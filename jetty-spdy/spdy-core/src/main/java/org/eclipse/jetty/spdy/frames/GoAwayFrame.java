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

import org.eclipse.jetty.spdy.api.SessionStatus;

public class GoAwayFrame extends ControlFrame
{
    private final int lastStreamId;
    private final int statusCode;

    public GoAwayFrame(short version, int lastStreamId, int statusCode)
    {
        super(version, ControlFrameType.GO_AWAY, (byte)0);
        this.lastStreamId = lastStreamId;
        this.statusCode = statusCode;
    }

    public int getLastStreamId()
    {
        return lastStreamId;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    @Override
    public String toString()
    {
        SessionStatus sessionStatus = SessionStatus.from(getStatusCode());
        return String.format("%s last_stream=%d status=%s", super.toString(), getLastStreamId(), sessionStatus == null ? getStatusCode() : sessionStatus);
    }
}
