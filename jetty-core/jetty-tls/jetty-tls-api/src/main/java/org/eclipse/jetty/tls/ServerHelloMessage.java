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
import org.eclipse.jetty.util.BufferUtil;

public record ServerHelloMessage(byte[] random, byte[] sessionId, CipherSuite cipherSuite, List<Extension> extensions) implements Message
{
    public ServerHelloMessage(byte[] random, CipherSuite cipherSuite, List<Extension> extensions)
    {
        this(random, BufferUtil.EMPTY_BYTES, cipherSuite, extensions);
    }

    @Override
    public Type type()
    {
        return Type.SERVER_HELLO;
    }
}
