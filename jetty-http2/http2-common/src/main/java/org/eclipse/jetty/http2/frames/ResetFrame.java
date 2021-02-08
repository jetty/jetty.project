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

import org.eclipse.jetty.http2.ErrorCode;

public class ResetFrame extends Frame
{
    public static final int RESET_LENGTH = 4;

    private final int streamId;
    private final int error;

    public ResetFrame(int streamId, int error)
    {
        super(FrameType.RST_STREAM);
        this.streamId = streamId;
        this.error = error;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getError()
    {
        return error;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d{%s}", super.toString(), streamId, ErrorCode.toString(error, null));
    }
}
