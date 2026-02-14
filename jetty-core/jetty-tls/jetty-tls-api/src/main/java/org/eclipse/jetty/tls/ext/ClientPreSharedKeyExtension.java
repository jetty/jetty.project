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
public record ClientPreSharedKeyExtension(List<PreSharedKeyIdentity> identities, List<byte[]> binders) implements Extension
{
    public static final int CODE = 0x0029;

    public ClientPreSharedKeyExtension
    {
        if (identities.size() != binders.size())
            throw new IllegalArgumentException("invalid number of identities and binders");
        if (identities.isEmpty())
            throw new IllegalArgumentException("missing identities");
        if (identities.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("invalid identity");
        if (binders.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("invalid binder");
    }

    @Override
    public int code()
    {
        return CODE;
    }
}
