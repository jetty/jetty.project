/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.WebSocket;

public class CaptureSocket implements WebSocket.OnTextMessage
{
    private final CountDownLatch latch = new CountDownLatch(1);
    public List<String> messages;

    public CaptureSocket()
    {
        messages = new ArrayList<String>();
    }

    public boolean awaitConnected(long timeout) throws InterruptedException
    {
        return latch.await(timeout, TimeUnit.MILLISECONDS);
    }

    public void onMessage(String data)
    {
        // System.out.printf("Received Message \"%s\" [size %d]%n", data, data.length());
        messages.add(data);
    }

    public void onOpen(Connection connection)
    {
        latch.countDown();
    }

    public void onClose(int closeCode, String message)
    {
    }
}
