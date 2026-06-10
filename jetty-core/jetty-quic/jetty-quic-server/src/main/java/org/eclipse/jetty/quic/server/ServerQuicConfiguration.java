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

import org.eclipse.jetty.quic.common.QuicConfiguration;

/**
 * <p>Server-side {@link QuicConfiguration} with server-specific settings.</p>
 */
public class ServerQuicConfiguration extends QuicConfiguration
{
    public ServerQuicConfiguration()
    {
        // Default configuration for a server.
        setSessionMaxData(24 * 1024 * 1024);
        setBidirectionalRemoteStreamMaxData(16 * 1024 * 1024);
        // Accept one bidirectional stream to simulate the TCP stream, and no unidirectional streams.
        setBidirectionalMaxStreams(1);
        setUnidirectionalMaxStreams(0);
    }
}
