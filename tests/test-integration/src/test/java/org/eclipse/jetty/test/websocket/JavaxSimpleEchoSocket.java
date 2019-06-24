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

package org.eclipse.jetty.test.websocket;

import java.util.concurrent.CountDownLatch;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static org.junit.jupiter.api.Assertions.fail;

@ClientEndpoint(
    subprotocols = {"chat"})
public class JavaxSimpleEchoSocket
{
    private static final Logger LOG = Log.getLogger(JavaxSimpleEchoSocket.class);
    private Session session;
    public CountDownLatch messageLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @OnError
    public void onError(Throwable t)
    {
        LOG.warn(t);
        fail(t.getMessage());
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        LOG.debug("Closed: {}, {}", close.getCloseCode().getCode(), close.getReasonPhrase());
        closeLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message)
    {
        LOG.debug("Received: {}", message);
        messageLatch.countDown();
    }

    @OnOpen
    public void onOpen(Session session)
    {
        LOG.debug("Opened");
        this.session = session;
    }
}
