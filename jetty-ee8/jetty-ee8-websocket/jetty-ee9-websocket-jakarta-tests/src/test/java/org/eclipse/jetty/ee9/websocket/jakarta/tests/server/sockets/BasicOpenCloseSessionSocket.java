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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/basic")
public class BasicOpenCloseSessionSocket extends TrackingSocket
{
    @OnClose
    public void onClose(CloseReason close, Session session)
    {
        addEvent("onClose(%s, %s)", close, session);
        this.closeReason = close;
        closeLatch.countDown();
    }

    @OnOpen
    public void onOpen(Session session)
    {
        addEvent("onOpen(%s)", session);
        openLatch.countDown();
    }
}
