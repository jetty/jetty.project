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

package org.eclipse.jetty.tls;

import java.util.Arrays;
import java.util.Objects;

public record KeyShare(NamedGroup group, byte[] keyExchange)
{
    @Override
    public int hashCode()
    {
        return Objects.hash(group, Arrays.hashCode(keyExchange));
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj == this)
            return true;
        if (obj instanceof KeyShare(NamedGroup thatGroup, byte[] thatKeyExchange))
            return Objects.equals(group, thatGroup) && Arrays.equals(keyExchange, thatKeyExchange);
        return false;
    }
}
