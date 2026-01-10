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

public class DataBlockedFrame extends Frame.Abstract
{
    private final long offset;

    public DataBlockedFrame(long offset)
    {
        super(0x14);
        this.offset = offset;
    }

    public long offset()
    {
        return offset;
    }

    @Override
    public String toString()
    {
        return "%s[offset=%d]".formatted(super.toString(), offset());
    }
}
