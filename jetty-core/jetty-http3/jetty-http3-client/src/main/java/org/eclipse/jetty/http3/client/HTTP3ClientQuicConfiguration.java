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

public class HTTP3ClientQuicConfiguration
{
    public static <T extends ClientQuicConfiguration> T configure(T quicConfiguration)
    {
        // Allow the mandatory unidirectional streams, no pushed streams.
        quicConfiguration.setUnidirectionalMaxStreams(8);
        quicConfiguration.setUnidirectionalStreamMaxData(4 * 1024 * 1024);
        return quicConfiguration;
    }

    private HTTP3ClientQuicConfiguration()
    {
    }
}
