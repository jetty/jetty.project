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

public class StopSendingFrame extends Frame.WithStreamId.Abstract
{
    private final long appErrorCode;

    public StopSendingFrame(long streamId, long appErrorCode)
    {
        super(0x05, streamId);
        this.appErrorCode = appErrorCode;
    }

    public long applicationErrorCode()
    {
        return appErrorCode;
    }

    @Override
    public String toString()
    {
        return "%s[appError=%d]".formatted(super.toString(), applicationErrorCode());
    }
}
