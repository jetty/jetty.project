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

package org.eclipse.jetty.ee10.websocket.tests.autobahn;

import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.core.WebSocketConstants;

@WebSocket
public class JettyAutobahnSocket extends EchoSocket
{
    @Override
    public void onOpen(Session session)
    {
        super.onOpen(session);
        session.setMaxTextMessageSize(Long.MAX_VALUE);
        session.setMaxBinaryMessageSize(Long.MAX_VALUE);
        session.setMaxFrameSize(WebSocketConstants.DEFAULT_MAX_FRAME_SIZE * 2);
    }
}
