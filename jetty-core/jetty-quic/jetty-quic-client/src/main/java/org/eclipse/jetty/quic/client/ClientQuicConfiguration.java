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

import org.eclipse.jetty.quic.common.QuicConfiguration;

public class ClientQuicConfiguration extends QuicConfiguration
{
    public ClientQuicConfiguration()
    {
        // Default configuration for a client.
        setSessionMaxData(16 * 1024 * 1024);
        setBidirectionalLocalStreamMaxData(8 * 1024 * 1024);
        // Do not accept streams initiated by the server.
        setBidirectionalMaxStreams(0);
        setUnidirectionalMaxStreams(0);
    }
}
