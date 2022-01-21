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

package org.eclipse.jetty.websocket.tests.javax;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ClientEndpoint(configurator = HostConfigurator.class)
@ServerEndpoint("/")
public class JavaxAutobahnSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(JavaxAutobahnSocket.class);

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
