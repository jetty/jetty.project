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

package org.eclipse.jetty.tls.ext;

import java.util.Arrays;
import java.util.Objects;

public record PreSharedKeyIdentity(byte[] identity, int obfuscatedTicketAge)
{
    public PreSharedKeyIdentity
    {
        Objects.requireNonNull(identity);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(Arrays.hashCode(identity()), obfuscatedTicketAge());
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj == this)
            return true;
        if (obj instanceof PreSharedKeyIdentity(byte[] thatIdentity, int thatObfuscatedTicketAge))
            return Arrays.equals(identity(), thatIdentity) && obfuscatedTicketAge() == thatObfuscatedTicketAge;
        return false;
    }
}
