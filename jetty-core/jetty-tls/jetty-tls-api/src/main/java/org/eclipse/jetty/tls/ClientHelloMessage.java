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

import java.util.List;

import org.eclipse.jetty.tls.ext.Extension;

public record ClientHelloMessage(byte[] random, List<CipherSuite> cipherSuites, List<Extension> extensions) implements Message
{
    public ClientHelloMessage
    {
        if (random.length != 32)
            throw new IllegalArgumentException("invalid_random_length");
        if (cipherSuites.isEmpty())
            throw new IllegalArgumentException("invalid_cipher_suites");
        if (extensions.isEmpty())
            throw new IllegalArgumentException("invalid_extensions");
    }

    @Override
    public Type type()
    {
        return Type.CLIENT_HELLO;
    }
}
