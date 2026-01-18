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

package org.eclipse.jetty.quic.server;

import org.eclipse.jetty.util.ssl.SslContextFactory;

public class QuicServerQuicConfiguration extends ServerQuicConfiguration
{
    private int destinationConnectionIdLength = 8;

    public void configure(SslContextFactory.Server sslContextFactory) throws Exception
    {
        getImplementationConfiguration().put(SslContextFactory.Server.class.getName(), sslContextFactory);
    }

    public void deconfigure(SslContextFactory.Server sslContextFactory)
    {
        getImplementationConfiguration().remove(SslContextFactory.Server.class.getName());
    }

    public int getDestinationConnectionIdLength()
    {
        return destinationConnectionIdLength;
    }

    public void setDestinationConnectionIdLength(int destinationConnectionIdLength)
    {
        if (destinationConnectionIdLength < 0 || destinationConnectionIdLength > 20)
            throw new IllegalArgumentException("invalid destinationConnectionId length: " + destinationConnectionIdLength);
        this.destinationConnectionIdLength = destinationConnectionIdLength;
    }
}
