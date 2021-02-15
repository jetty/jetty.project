//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.server.helper;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Simple Echo WebSocket, using async writes of echo
 */
@WebSocket
public class EchoSocket
{
    private static Logger LOG = Log.getLogger(EchoSocket.class);

    private Session session;

    @OnWebSocketMessage
    public void onBinary(byte[] buf, int offset, int len) throws IOException
    {
        LOG.debug("onBinary(byte[{}],{},{})", buf.length, offset, len);

        // echo the message back.
        ByteBuffer data = ByteBuffer.wrap(buf, offset, len);
        RemoteEndpoint remote = this.session.getRemote();
        remote.sendBytes(data, null);
        if (remote.getBatchMode() == BatchMode.ON)
            remote.flush();
    }

    @OnWebSocketConnect
    public void onOpen(Session sess)
    {
        this.session = sess;
    }

    @OnWebSocketMessage
    public void onText(String message) throws IOException
    {
        LOG.debug("onText({})", message);

        // echo the message back.
        RemoteEndpoint remote = session.getRemote();
        remote.sendString(message, null);
        if (remote.getBatchMode() == BatchMode.ON)
            remote.flush();
    }
}
