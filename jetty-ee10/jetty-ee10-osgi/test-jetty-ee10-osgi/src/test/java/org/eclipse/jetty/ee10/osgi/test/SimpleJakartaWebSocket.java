//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.osgi.test;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

import static org.junit.Assert.fail;

@ClientEndpoint(
    subprotocols = {"chat"})
public class SimpleJakartaWebSocket
{
    private static final Logger LOG = Logger.getLogger(SimpleJakartaWebSocket.class.getName());
    private Session session;
    public CountDownLatch messageLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @OnError
    public void onError(Throwable t)
    {
        LOG.log(Level.WARNING, "onError", t);
        fail(t.getMessage());
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        LOG.info(String.format("Closed: %d, \"%s\"", close.getCloseCode().getCode(), close.getReasonPhrase()));
        closeLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message)
    {
        LOG.info(String.format("Received: \"%s\"", message));
        messageLatch.countDown();
    }

    @OnOpen
    public void onOpen(Session session)
    {
        LOG.info("Opened");
        this.session = session;
    }
}
