//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.api.frames;

import org.eclipse.jetty.quic.api.Session;

public final class ConnectionCloseFrame extends Frame.Abstract
{
    private final long errorCode;
    private final String reason;
    private final long causeFrameType;

    /// Creates a connection close frame with the given application error code and reason.
    ///
    /// Applications should use this constructor in conjunction with
    /// [Session#close(ConnectionCloseFrame, org.eclipse.jetty.util.Promise.Invocable)].
    ///
    /// @param appErrorCode the application error code
    /// @param reason the application error reason
    public ConnectionCloseFrame(long appErrorCode, String reason)
    {
        this(0x1D, appErrorCode, reason, 0);
    }

    /// Creates a connection close frame with the given QUIC error code, reason, and frame type.
    ///
    /// Applications should not use this constructor.
    ///
    /// @param quicErrorCode the QUIC error code
    /// @param reason the QUIC error reason
    /// @param causeFrameType the frame type that caused the error
    public ConnectionCloseFrame(long quicErrorCode, String reason, long causeFrameType)
    {
        this(0x1C, quicErrorCode, reason, causeFrameType);
    }

    private ConnectionCloseFrame(long frameType, long errorCode, String reason, long causeFrameType)
    {
        super(frameType);
        this.errorCode = errorCode;
        this.reason = reason;
        this.causeFrameType = causeFrameType;
    }

    public long errorCode()
    {
        return errorCode;
    }

    public String reason()
    {
        return reason;
    }

    public long causeFrameType()
    {
        return causeFrameType;
    }

    @Override
    public String toString()
    {
        boolean appError = type() == 0x1D;
        if (appError)
            return "%s[appError=0x%x,reason=%s]".formatted(super.toString(), errorCode(), reason());
        return "%s[quicError=0x%x,reason=%s,causeFrame=0x%x]".formatted(super.toString(), errorCode(), reason(), causeFrameType());
    }
}
