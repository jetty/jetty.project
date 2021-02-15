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

package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Example Socket for echoing back Big data using the Annotation techniques along with stateless techniques.
 */
@WebSocket(maxTextMessageSize = 64 * 1024, maxBinaryMessageSize = 64 * 1024)
public class BigEchoSocket
{
    private static final Logger LOG = Log.getLogger(BigEchoSocket.class);

    @OnWebSocketMessage
    public void onBinary(Session session, byte[] buf, int offset, int length) throws IOException
    {
        if (!session.isOpen())
        {
            LOG.warn("Session is closed");
            return;
        }
        RemoteEndpoint remote = session.getRemote();
        remote.sendBytes(ByteBuffer.wrap(buf, offset, length), null);
        if (remote.getBatchMode() == BatchMode.ON)
            remote.flush();
    }

    @OnWebSocketMessage
    public void onText(Session session, String message) throws IOException
    {
        if (!session.isOpen())
        {
            LOG.warn("Session is closed");
            return;
        }
        RemoteEndpoint remote = session.getRemote();
        remote.sendString(message, null);
        if (remote.getBatchMode() == BatchMode.ON)
            remote.flush();
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onError()", cause);
        }
    }
}
