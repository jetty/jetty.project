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

package org.eclipse.jetty.quic.tls.message;

import java.util.HashMap;
import java.util.Map;

public enum TLSVersion
{
    TLS_1_2(0x0303),
    TLS_1_3(0x0304);

    private final int value;

    TLSVersion(int value)
    {
        this.value = value;
        Values.VALUES.put(value, this);
    }

    public int value()
    {
        return value;
    }

    public static TLSVersion from(int value)
    {
        return Values.VALUES.get(value);
    }

    private static class Values
    {
        private static final Map<Integer, TLSVersion> VALUES = new HashMap<>();
    }
}
