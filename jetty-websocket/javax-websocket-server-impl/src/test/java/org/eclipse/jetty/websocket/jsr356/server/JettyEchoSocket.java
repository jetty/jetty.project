//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * This is a Jetty API version of a websocket.
 * <p>
 * This is used a a client socket during the server tests.
 */
@WebSocket
public class JettyEchoSocket
{
    private static final Logger LOG = Log.getLogger(JettyEchoSocket.class);
    @SuppressWarnings("unused")
    private Session session;
    private RemoteEndpoint remote;
    private Boolean closed = null;
    private EventQueue<String> incomingMessages = new EventQueue<>();

    public Queue<String> awaitMessages(int expected) throws TimeoutException, InterruptedException
    {
        incomingMessages.awaitEventCount(expected,2,TimeUnit.SECONDS);
        return incomingMessages;
    }

    public Boolean getClosed()
    {
        return closed;
    }

    @OnWebSocketClose
    public void onClose(int code, String reason)
    {
        closed = true;
        session = null;
        remote = null;
    }

    @OnWebSocketError
    public void onError(Throwable t)
    {
        LOG.warn(t);
    }

    @OnWebSocketMessage
    public void onMessage(String msg)
    {
        incomingMessages.add(msg);
        remote.sendString(msg,null);
    }

    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.closed = false;
        this.session = session;
        this.remote = session.getRemote();
    }

    public void sendMessage(String msg)
    {
        remote.sendStringByFuture(msg);
    }
}
