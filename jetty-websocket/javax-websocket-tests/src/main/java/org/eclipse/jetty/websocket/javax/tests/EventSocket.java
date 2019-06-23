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

package org.eclipse.jetty.websocket.javax.tests;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ServerEndpoint("/")
@ClientEndpoint
public class EventSocket
{
    private static final Logger LOG = Log.getLogger(EventSocket.class);

    public Session session;

    public BlockingQueue<String> messageQueue = new BlockingArrayQueue<>();
    public volatile Throwable error = null;

    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
        LOG.info("{}  onOpen(): {}", toString(), session);
        openLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message) throws IOException
    {
        LOG.info("{}  onMessage(): {}", toString(), message);
        messageQueue.offer(message);
    }

    @OnClose
    public void onClose(CloseReason reason)
    {
        LOG.info("{}  onClose(): {}", toString(), reason);
        closeLatch.countDown();
    }

    @OnError
    public void onError(Throwable cause)
    {
        LOG.info("{}  onError(): {}", toString(), cause);
        error = cause;
    }
}
