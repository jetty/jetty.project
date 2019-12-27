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

package org.eclipse.jetty.websocket.javax.tests.autobahn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import javax.websocket.ClientEndpoint;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ClientEndpoint
@ServerEndpoint("/")
public class JavaxAutobahnSocket
{
    private static final Logger LOG = Log.getLogger(JavaxAutobahnSocket.class);

    public Session session;
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @OnOpen
    public void onConnect(Session session)
    {
        this.session = session;
        session.setMaxTextMessageBufferSize(Integer.MAX_VALUE);
        session.setMaxBinaryMessageBufferSize(Integer.MAX_VALUE);
    }

    @OnMessage
    public void onText(String message) throws IOException
    {
        session.getBasicRemote().sendText(message);
    }

    @OnMessage
    public void onBinary(ByteBuffer message) throws IOException
    {
        session.getBasicRemote().sendBinary(message);
    }

    @OnError
    public void onError(Throwable t)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onError()", t);
    }

    @OnClose
    public void onClose()
    {
        closeLatch.countDown();
    }
}
