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

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.StreamStatus;

public class StreamException extends RuntimeException
{
    private final int streamId;
    private final StreamStatus streamStatus;

    public StreamException(int streamId, StreamStatus streamStatus)
    {
        this.streamId = streamId;
        this.streamStatus = streamStatus;
    }

    public StreamException(int streamId, StreamStatus streamStatus, String message)
    {
        super(message);
        this.streamId = streamId;
        this.streamStatus = streamStatus;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public StreamStatus getStreamStatus()
    {
        return streamStatus;
    }
}
