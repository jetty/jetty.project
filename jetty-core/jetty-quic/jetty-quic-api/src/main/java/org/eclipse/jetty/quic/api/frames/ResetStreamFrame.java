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

public final class ResetStreamFrame extends Frame.WithStreamId.Abstract implements Frame.WithOffset
{
    private final long appErrorCode;
    private final long finalSize;

    public ResetStreamFrame(long streamId, long appErrorCode, long finalSize)
    {
        super(0x04, streamId);
        this.appErrorCode = appErrorCode;
        this.finalSize = finalSize;
    }

    public long applicationErrorCode()
    {
        return appErrorCode;
    }

    public long finalSize()
    {
        return finalSize;
    }

    @Override
    public long offset()
    {
        return finalSize();
    }

    @Override
    public long length()
    {
        return 0;
    }

    @Override
    public String toString()
    {
        return "%s[appError=%d,finalSize=%d]".formatted(
            super.toString(),
            applicationErrorCode(),
            finalSize()
        );
    }
}
