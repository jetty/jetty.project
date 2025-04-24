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

package org.eclipse.jetty.http3.client;

import org.eclipse.jetty.quic.client.ClientQuicConfiguration;

/**
 * <p>Helper class to configure QUIC in a suitable way for an HTTP/3 client.</p>
 */
public class HTTP3ClientQuicConfiguration
{
    /**
     * <p>Configures the given {@link ClientQuicConfiguration}
     * with default values that are suitable for an HTTP/3 client.</p>
     * <p>Applications can further customize the returned
     * {@link ClientQuicConfiguration}, or change the default
     * values set by this method.</p>
     *
     * @param quicConfiguration the {@link ClientQuicConfiguration} to configure for HTTP/3.
     * @return the configured {@link ClientQuicConfiguration}
     * @param <T> the {@link ClientQuicConfiguration} subtype
     */
    public static <T extends ClientQuicConfiguration> T configure(T quicConfiguration)
    {
        // Allow the mandatory unidirectional streams, no pushed streams.
        quicConfiguration.setUnidirectionalMaxStreams(8);
        quicConfiguration.setUnidirectionalStreamMaxData(1024 * 1024);
        return quicConfiguration;
    }

    private HTTP3ClientQuicConfiguration()
    {
    }
}
