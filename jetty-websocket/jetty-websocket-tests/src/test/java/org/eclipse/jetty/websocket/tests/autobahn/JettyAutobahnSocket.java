//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.autobahn;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.tests.EchoSocket;

@WebSocket
public class JettyAutobahnSocket extends EchoSocket
{
    @Override
    public void onOpen(Session session)
    {
        super.onOpen(session);
        session.setMaxTextMessageSize(Long.MAX_VALUE);
        session.setMaxBinaryMessageSize(Long.MAX_VALUE);
        session.setMaxFrameSize(WebSocketConstants.DEFAULT_MAX_FRAME_SIZE*2);
    }
}
