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

package org.eclipse.jetty.websocket.server.helper;

import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.util.Sha1Sum;

public class CaptureSocket extends WebSocketAdapter
{
    private final CountDownLatch latch = new CountDownLatch(1);
    public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

    public boolean awaitConnected(long timeout) throws InterruptedException
    {
        return latch.await(timeout, TimeUnit.MILLISECONDS);
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

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        try
        {
            messages.add("binary[sha1=" + Sha1Sum.calculate(payload, offset, len) + "]");
        }
        catch (NoSuchAlgorithmException e)
        {
            messages.add("ERROR: Unable to calculate Binary SHA1: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
