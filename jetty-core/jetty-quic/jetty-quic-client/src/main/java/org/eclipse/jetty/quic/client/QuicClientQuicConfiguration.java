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

package org.eclipse.jetty.quic.client;

import org.eclipse.jetty.util.ssl.SslContextFactory;

public class QuicClientQuicConfiguration extends ClientQuicConfiguration
{
    public void configure(SslContextFactory.Client sslContextFactory)
    {
        getImplementationConfiguration().put(SslContextFactory.Client.class.getName(), sslContextFactory);
    }

    public void deconfigure(SslContextFactory.Client sslContextFactory)
    {
        getImplementationConfiguration().remove(SslContextFactory.Client.class.getName());
    }
}
