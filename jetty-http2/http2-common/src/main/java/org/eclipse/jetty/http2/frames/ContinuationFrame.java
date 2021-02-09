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

public class ContinuationFrame extends Frame
{
    private final int streamId;
    private final boolean endHeaders;

    public ContinuationFrame(int streamId, boolean endHeaders)
    {
        super(FrameType.CONTINUATION);
        this.streamId = streamId;
        this.endHeaders = endHeaders;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public boolean isEndHeaders()
    {
        return endHeaders;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d{end=%b}", super.toString(), getStreamId(), isEndHeaders());
    }
}
