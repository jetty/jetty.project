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

public final class CertificateRequest implements Message
{
    private final byte[] context;
    private final List<Extension> extensions;

    public CertificateRequest(byte[] context, List<Extension> extensions)
    {
        this.context = context;
        this.extensions = extensions;
    }

    @Override
    public Type getType()
    {
        return Type.CERTIFICATE_REQUEST;
    }

    public byte[] getContext()
    {
        return context;
    }

    public List<Extension> getExtensions()
    {
        return extensions;
    }
}
