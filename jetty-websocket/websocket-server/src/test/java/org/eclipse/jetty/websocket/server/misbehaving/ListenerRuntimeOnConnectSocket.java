//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.misbehaving;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

public class ListenerRuntimeOnConnectSocket extends WebSocketAdapter
{
    public LinkedList<Throwable> errors = new LinkedList<>();
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public int closeStatusCode;
    public String closeReason;

    @Override
    public void onWebSocketConnect(Session sess)
    {
        super.onWebSocketConnect(sess);

        throw new RuntimeException("Intentional Exception from onWebSocketConnect");
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        closeLatch.countDown();
        closeStatusCode = statusCode;
        closeReason = reason;
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        this.errors.add(cause);
    }

    @Override
    public void onWebSocketText(String message)
    {
        getRemote().sendStringByFuture(message);
    }

    public void reset()
    {
        this.closeLatch = new CountDownLatch(1);
        this.closeStatusCode = -1;
        this.closeReason = null;
        this.errors.clear();
    }
}
