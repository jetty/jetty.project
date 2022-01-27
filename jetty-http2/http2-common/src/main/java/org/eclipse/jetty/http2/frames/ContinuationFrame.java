//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
