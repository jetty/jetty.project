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

public class HTTP3ServerQuicConfiguration
{
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
