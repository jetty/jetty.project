//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

/**
 * HttpServer Tester.
 */
public class ServerConnectorHttpServerTest extends HttpServerTestBase
{
    @BeforeEach
    public void init() throws Exception
    {
        // Run this test with 0 acceptors. Other tests already check the acceptors >0
        startServer(new ServerConnector(_server, 0, 1));
    }
}
