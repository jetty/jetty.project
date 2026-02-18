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

import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.tls.ClientHelloMessage;

/// The pre-shared key extension for the [ClientHelloMessage].
public record ClientPreSharedKeyExtension(List<PreSharedKeyIdentity> identities, boolean hasBinders) implements Extension
{
    public static final int CODE = 0x0029;

    public ClientPreSharedKeyExtension
    {
        if (identities.isEmpty())
            throw new IllegalArgumentException("missing identities");
        if (identities.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("invalid identity");
    }

    @Override
    public int code()
    {
        return CODE;
    }
}
