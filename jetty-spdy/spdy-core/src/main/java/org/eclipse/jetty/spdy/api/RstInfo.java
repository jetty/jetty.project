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

package org.eclipse.jetty.spdy.api;

import java.util.concurrent.TimeUnit;

/**
 * <p>A container for RST_STREAM frames data: the stream id and the stream status.</p>
 */
public class RstInfo extends Info
{
    private final int streamId;
    private final StreamStatus streamStatus;

    /**
     * <p>Creates a new {@link RstInfo} with the given stream id and stream status</p>
     *
     * @param timeout      the operation's timeout
     * @param unit         the timeout's unit
     * @param streamId     the stream id
     * @param streamStatus the stream status
     */
    public RstInfo(long timeout, TimeUnit unit, int streamId, StreamStatus streamStatus)
    {
        super(timeout, unit);
        this.streamId = streamId;
        this.streamStatus = streamStatus;
    }

    /**
     * <p>Creates a new {@link RstInfo} with the given stream id and stream status</p>
     *
     * @param streamId
     * @param streamStatus
     */
    public RstInfo(int streamId, StreamStatus streamStatus)
    {
        this(0, TimeUnit.SECONDS, streamId, streamStatus);
    }

    /**
     * @return the stream id
     */
    public int getStreamId()
    {
        return streamId;
    }

    /**
     * @return the stream status
     */
    public StreamStatus getStreamStatus()
    {
        return streamStatus;
    }

    @Override
    public String toString()
    {
        return String.format("RST stream=%d %s", streamId, streamStatus);
    }
}
