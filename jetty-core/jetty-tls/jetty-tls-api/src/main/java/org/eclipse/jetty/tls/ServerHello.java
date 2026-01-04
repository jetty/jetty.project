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

public final class ServerHello implements Message
{
    private byte[] random;
    private byte[] sessionId;
    private CipherSuite cipherSuite;
    private List<Extension> extensions;

    @Override
    public Type getType()
    {
        return Type.SERVER_HELLO;
    }

    public byte[] getRandom()
    {
        return random;
    }

    public void setRandom(byte[] random)
    {
        this.random = random;
    }

    public byte[] getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(byte[] sessionId)
    {
        this.sessionId = sessionId;
    }

    public CipherSuite getCipherSuite()
    {
        return cipherSuite;
    }

    public void setCipherSuite(CipherSuite cipherSuite)
    {
        this.cipherSuite = cipherSuite;
    }

    public List<Extension> getExtensions()
    {
        return extensions;
    }

    public void setExtensions(List<Extension> extensions)
    {
        this.extensions = extensions;
    }
}
