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

import static org.hamcrest.Matchers.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.junit.Assert;

/**
 * Testing Socket used on client side WebSocket testing.
 */
public class TrackingSocket extends WebSocketAdapter
{
    private static final Logger LOG = Log.getLogger(TrackingSocket.class);

    public int closeCode = -1;
    public StringBuilder closeMessage = new StringBuilder();
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch dataLatch = new CountDownLatch(1);
    public BlockingQueue<String> messageQueue = new BlockingArrayQueue<String>();

    public void assertClose(int expectedStatusCode, String expectedReason) throws InterruptedException
    {
        assertCloseCode(expectedStatusCode);
        assertCloseReason(expectedReason);
    }

    public void assertCloseCode(int expectedCode) throws InterruptedException
    {
        Assert.assertThat("Was Closed",closeLatch.await(500,TimeUnit.MILLISECONDS),is(true));
        Assert.assertThat("Close Code",closeCode,is(expectedCode));
    }

    private void assertCloseReason(String expectedReason)
    {
        Assert.assertThat("Close Reaosn",closeMessage.toString(),is(expectedReason));
    }

    public void assertIsOpen() throws InterruptedException
    {
        assertWasOpened();
        assertNotClosed();
    }

    public void assertMessage(String expected)
    {
        String actual = messageQueue.poll();
        Assert.assertEquals("Message",expected,actual);
    }

    public void assertNotClosed()
    {
        Assert.assertThat("Closed Latch",closeLatch.getCount(),greaterThanOrEqualTo(1L));
    }

    public void assertNotOpened()
    {
        Assert.assertThat("Open Latch",openLatch.getCount(),greaterThanOrEqualTo(1L));
    }

    public void assertWasOpened() throws InterruptedException
    {
        Assert.assertThat("Was Opened",openLatch.await(500,TimeUnit.MILLISECONDS),is(true));
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        LOG.debug("onWebSocketBinary()");
        dataLatch.countDown();
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        super.onWebSocketClose(statusCode,reason);
        closeCode = statusCode;
        closeMessage.append(reason);
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        super.onWebSocketConnect(connection);
        openLatch.countDown();
    }

    @Override
    public void onWebSocketText(String message)
    {
        LOG.debug("onWebSocketText({})",message);
        messageQueue.add(message);
        dataLatch.countDown();
    }

    public void waitForMessage(TimeUnit timeoutUnit, int timeoutDuration) throws InterruptedException
    {
        Assert.assertThat("Message Received",dataLatch.await(timeoutDuration,timeoutUnit),is(true));
    }
}