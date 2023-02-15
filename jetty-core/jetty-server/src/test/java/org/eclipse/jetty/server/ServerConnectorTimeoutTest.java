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

package org.eclipse.jetty.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class ServerConnectorTimeoutTest extends ConnectorTimeoutTest
{
    @BeforeEach
    public void init() throws Exception
    {
        ServerConnector connector = new ServerConnector(_server, 1, 1);
        connector.setIdleTimeout(MAX_IDLE_TIME);
        initServer(connector);
    }

    @Test
    public void testStartStopStart()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            _server.stop();
            _server.start();
        });
    }
}
