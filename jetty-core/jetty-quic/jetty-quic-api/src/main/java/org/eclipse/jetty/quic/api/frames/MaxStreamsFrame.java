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

public class MaxStreamsFrame extends Frame.Abstract
{
    private final long maxStreams;

    public MaxStreamsFrame(long maxStreams, boolean bidirectional)
    {
        super(bidirectional ? 0x12 : 0x13);
        this.maxStreams = maxStreams;
    }

    public boolean isBidirectional()
    {
        return type() == 0x12;
    }

    public long maxStreams()
    {
        return maxStreams;
    }

    @Override
    public String toString()
    {
        return "%s[%s,maxStreams=%d]".formatted(super.toString(), isBidirectional() ? "bi" : "uni", maxStreams());
    }
}
