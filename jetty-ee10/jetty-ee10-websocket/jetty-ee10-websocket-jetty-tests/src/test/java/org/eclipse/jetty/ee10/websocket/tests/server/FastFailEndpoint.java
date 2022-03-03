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

package org.eclipse.jetty.ee10.websocket.tests.server;

import org.eclipse.jetty.ee10.websocket.api.Session;

/**
 * On Connect, throw unhandled exception
 */
public class FastFailEndpoint extends AbstractCloseEndpoint
{
    @Override
    public void onWebSocketConnect(Session sess)
    {
        log.debug("onWebSocketConnect({})", sess);
        // Test failure due to unhandled exception
        // this should trigger a fast-fail closure during open/connect
        throw new RuntimeException("Intentional FastFail");
    }
}
