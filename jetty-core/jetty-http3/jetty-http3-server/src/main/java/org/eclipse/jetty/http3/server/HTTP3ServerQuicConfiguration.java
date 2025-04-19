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

package org.eclipse.jetty.http3.server;

import org.eclipse.jetty.quic.server.ServerQuicConfiguration;

/**
 * <p>Helper class to configure QUIC in a suitable way for an HTTP/3 server.</p>
 */
public class HTTP3ServerQuicConfiguration
{
    /**
     * <p>Configures the given {@link ServerQuicConfiguration}
     * with default values that are suitable for an HTTP/3 server.</p>
     * <p>Applications can further customize the returned
     * {@link ServerQuicConfiguration}, or change the default
     * values set by this method.</p>
     *
     * @param quicConfiguration the {@link ServerQuicConfiguration} to configure for HTTP/3.
     * @return the configured {@link ServerQuicConfiguration}
     * @param <T> the {@link ServerQuicConfiguration} subtype
     */
    public static <T extends ServerQuicConfiguration> T configure(T quicConfiguration)
    {
        // Max number of streams that a client can open.
        quicConfiguration.setBidirectionalMaxStreams(128 * 1024);
        // HTTP/3 requires a few mandatory unidirectional streams.
        quicConfiguration.setUnidirectionalMaxStreams(8);
        quicConfiguration.setUnidirectionalStreamMaxData(1024 * 1024);
        return quicConfiguration;
    }

    private HTTP3ServerQuicConfiguration()
    {
    }
}
