//
// ========================================================================
// Copyright (c) 2019 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.server.sockets;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/echo/close/session/reason")
public class BasicCloseSessionReasonSocket extends TrackingSocket
{
    @OnClose
    public void onClose(Session session, CloseReason reason)
    {
        addEvent("onClose(%s,%s)", session, reason);
        closeLatch.countDown();
    }
}
