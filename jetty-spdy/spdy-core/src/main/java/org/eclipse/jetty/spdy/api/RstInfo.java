//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

/**
 * <p>A container for RST_STREAM frames data: the stream id and the stream status.</p>
 */
public class RstInfo
{
    private final int streamId;
    private final StreamStatus streamStatus;

    /**
     * <p>Creates a new {@link RstInfo} with the given stream id and stream status</p>
     *
     * @param streamId  the stream id
     * @param streamStatus the stream status
     */
    public RstInfo(int streamId, StreamStatus streamStatus)
    {
        this.streamId = streamId;
        this.streamStatus = streamStatus;
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
