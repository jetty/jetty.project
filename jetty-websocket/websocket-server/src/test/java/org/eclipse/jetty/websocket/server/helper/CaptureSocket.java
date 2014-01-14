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

package org.eclipse.jetty.websocket.server.helper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

public class CaptureSocket extends WebSocketAdapter
{
    private final CountDownLatch latch = new CountDownLatch(1);
    public EventQueue<String> messages;

    public CaptureSocket()
    {
        messages = new EventQueue<String>();
    }

    public boolean awaitConnected(long timeout) throws InterruptedException
    {
        return latch.await(timeout,TimeUnit.MILLISECONDS);
    }

    public void close()
    {
        getSession().close();
    }

    @Override
    public void onWebSocketConnect(Session sess)
    {
        super.onWebSocketConnect(sess);
        latch.countDown();
    }

    @Override
    public void onWebSocketText(String message)
    {
        // System.out.printf("Received Message \"%s\" [size %d]%n", message, message.length());
        messages.add(message);
    }
}
