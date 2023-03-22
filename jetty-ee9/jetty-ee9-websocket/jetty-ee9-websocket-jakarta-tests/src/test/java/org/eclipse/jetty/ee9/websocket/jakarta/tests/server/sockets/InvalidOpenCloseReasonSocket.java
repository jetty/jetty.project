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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnOpen;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/invalid")
public class InvalidOpenCloseReasonSocket extends TrackingSocket
{
    /**
     * Invalid Open Method Declaration (parameter type CloseReason)
     *
     * @param reason the close reason
     */
    @OnOpen
    public void onOpen(CloseReason reason)
    {
        openLatch.countDown();
    }
}
