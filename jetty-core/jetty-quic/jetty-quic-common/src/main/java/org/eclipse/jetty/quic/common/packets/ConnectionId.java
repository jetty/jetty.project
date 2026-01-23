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

package org.eclipse.jetty.quic.common.packets;

import java.util.Arrays;

import org.eclipse.jetty.util.StringUtil;

// TODO: make this class private/internal?
public record ConnectionId(byte[] bytes)
{
    @Override
    public int hashCode()
    {
        return Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof ConnectionId that)
            return Arrays.equals(bytes, that.bytes);
        return false;
    }

    @Override
    public String toString()
    {
        return "%s[%s]".formatted(getClass().getSimpleName(), StringUtil.toHexString(bytes));
    }
}
