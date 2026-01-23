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

package org.eclipse.jetty.quic.common.tls;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.util.TypeUtil;

public class TLSConfiguration
{
    private final List<Extension> extensions = new ArrayList<>();
    private List<String> applicationProtocols;

    public void addExtension(Extension extension)
    {
        extensions.add(extension);
    }

    public List<String> getApplicationProtocols()
    {
        return applicationProtocols;
    }

    public void setApplicationProtocols(List<String> applicationProtocols)
    {
        this.applicationProtocols = applicationProtocols;
    }

    public List<Extension> getExtensions()
    {
        return extensions;
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }
}
