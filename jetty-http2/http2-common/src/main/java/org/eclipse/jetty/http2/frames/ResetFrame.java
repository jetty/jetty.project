//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.Locale;

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
        ErrorCode errorCode = ErrorCode.from(error);
        String reason = errorCode == null ? "error=" + error : errorCode.name().toLowerCase(Locale.ENGLISH);
        return String.format("%s#%d{%s}", super.toString(), streamId, reason);
    }
}
