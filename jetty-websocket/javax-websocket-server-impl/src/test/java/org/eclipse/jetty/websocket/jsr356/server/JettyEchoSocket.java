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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
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
    private Lock remoteLock = new ReentrantLock();
    private RemoteEndpoint remote;
    public LinkedBlockingQueue<String> incomingMessages = new LinkedBlockingQueue<>();

    public boolean getClosed()
    {
        remoteLock.lock();
        try
        {
            return (remote == null);
        }
        finally
        {
            remoteLock.unlock();
        }
    }

    @OnWebSocketClose
    public void onClose(int code, String reason)
    {
        session = null;
        remoteLock.lock();
        try
        {
            remote = null;
        }
        finally
        {
            remoteLock.unlock();
        }
    }

    @OnWebSocketError
    public void onError(Throwable t)
    {
        LOG.warn(t);
    }

    @OnWebSocketMessage
    public void onMessage(String msg) throws IOException
    {
        incomingMessages.add(msg);
        sendMessage(msg);
    }

    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.session = session;
        remoteLock.lock();
        try
        {
            this.remote = session.getRemote();
        }
        finally
        {
            remoteLock.unlock();
        }
    }

    public void sendMessage(String msg) throws IOException
    {
        remoteLock.lock();
        try
        {
            RemoteEndpoint r = remote;
            if (r == null)
            {
                return;
            }

            r.sendStringByFuture(msg);
            if (r.getBatchMode() == BatchMode.ON)
                r.flush();
        }
        finally
        {
            remoteLock.unlock();
        }
    }
}
