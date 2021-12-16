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

package org.eclipse.jetty.quic.common;

public class CloseInfo
{
    private final int error;
    private final String reason;

    public CloseInfo(int error, String reason)
    {
        this.error = error;
        this.reason = reason;
    }

    public int error()
    {
        return error;
    }

    public String reason()
    {
        return reason;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[error=%d,reason=%s]", getClass().getSimpleName(), hashCode(), error(), reason());
    }
}
