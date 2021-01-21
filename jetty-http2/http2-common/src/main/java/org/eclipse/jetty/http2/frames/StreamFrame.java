//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

public abstract class StreamFrame extends Frame
{
    private final int streamId;

    public StreamFrame(FrameType type, int streamId)
    {
        super(type);
        this.streamId = streamId;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public abstract StreamFrame withStreamId(int streamId);
}
