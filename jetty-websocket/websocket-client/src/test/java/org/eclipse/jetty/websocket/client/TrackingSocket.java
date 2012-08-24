//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.junit.Assert;

import static org.hamcrest.Matchers.is;

public class TrackingSocket extends WebSocketAdapter
{
    public AtomicBoolean open = new AtomicBoolean(false);
    public AtomicInteger close = new AtomicInteger(-1);
    public StringBuilder closeMessage = new StringBuilder();
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch dataLatch = new CountDownLatch(1);
    public BlockingQueue<String> messageQueue = new BlockingArrayQueue<String>();

    public void assertClose(int expectedStatusCode, String expectedReason)
    {
        assertCloseCode(expectedStatusCode);
        assertCloseReason(expectedReason);
    }

    public void assertCloseCode(int expectedCode)
    {
        Assert.assertThat("Close Code",close.get(),is(expectedCode));
    }

    private void assertCloseReason(String expectedReason)
    {
        Assert.assertThat("Close Reaosn",closeMessage.toString(),is(expectedReason));
    }

    public void assertIsOpen()
    {
        assertWasOpened();
        assertNotClosed();
    }

    public void assertMessage(String string)
    {
        // TODO Auto-generated method stub

    }

    public void assertNotClosed()
    {
        Assert.assertThat("Close Code",close.get(),is(-1));
    }

    public void assertNotOpened()
    {
        Assert.assertThat("Opened State",open.get(),is(false));
    }

    public void assertWasOpened()
    {
        Assert.assertThat("Opened State",open.get(),is(true));
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        dataLatch.countDown();
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        super.onWebSocketClose(statusCode,reason);
        close.set(statusCode);
        closeMessage.append(reason);
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        super.onWebSocketConnect(connection);
        open.set(true);
        openLatch.countDown();
    }

    @Override
    public void onWebSocketText(String message)
    {
        dataLatch.countDown();
        messageQueue.add(message);
    }

    public void waitForResponseMessage()
    {
        // TODO Auto-generated method stub

    }
}
