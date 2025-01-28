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

public class ConnectionCloseFrame extends Frame
{
    private final long errorCode;
    private final String reason;
    private final long causeFrameType;

    /**
     * <p>Creates a connection close frame with the given application error code and reason.</p>
     * <p>Applications should use this constructor in conjunction with
     * {@link Session#close(ConnectionCloseFrame)}.
     *
     * @param appErrorCode the application error code
     * @param reason the application error reason
     */
    public ConnectionCloseFrame(long appErrorCode, String reason)
    {
        this(0x1D, appErrorCode, reason, 0);
    }

    /**
     * <p>Creates a connection close frame with the given QUIC error code, reason, and frame type.</p>
     * <p>Applications should not use this constructor.</p>
     *
     * @param quicErrorCode the QUIC error code
     * @param reason the QUIC error reason
     * @param causeFrameType the frame type that caused the error
     */
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

    public long getErrorCode()
    {
        return errorCode;
    }

    public String getReason()
    {
        return reason;
    }

    public long getCauseFrameType()
    {
        return causeFrameType;
    }

    @Override
    public String toString()
    {
        boolean appError = getFrameType() == 0x1D;
        if (appError)
            return "%s[appError=0x%x,reason=%s]".formatted(super.toString(), getErrorCode(), getReason());
        return "%s[quicError0x=%x,reason=%s,frame=%d]".formatted(super.toString(), getErrorCode(), getReason(), getCauseFrameType());
    }
}
