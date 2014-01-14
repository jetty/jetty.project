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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.junit.Assert;

/**
 * Testing Socket used on client side WebSocket testing.
 */
public class JettyTrackingSocket extends WebSocketAdapter
{
    private static final Logger LOG = Log.getLogger(JettyTrackingSocket.class);

    public int closeCode = -1;
    public Exchanger<String> messageExchanger;
    public StringBuilder closeMessage = new StringBuilder();
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch dataLatch = new CountDownLatch(1);
    public EventQueue<String> messageQueue = new EventQueue<>();
    public EventQueue<Throwable> errorQueue = new EventQueue<>();

    public void assertClose(int expectedStatusCode, String expectedReason) throws InterruptedException
    {
        assertCloseCode(expectedStatusCode);
        assertCloseReason(expectedReason);
    }

    public void assertCloseCode(int expectedCode) throws InterruptedException
    {
        Assert.assertThat("Was Closed",closeLatch.await(50,TimeUnit.MILLISECONDS),is(true));
        Assert.assertThat("Close Code / Received [" + closeMessage + "]",closeCode,is(expectedCode));
    }

    private void assertCloseReason(String expectedReason)
    {
        Assert.assertThat("Close Reason",closeMessage.toString(),is(expectedReason));
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

    public void awaitMessage(int expectedMessageCount, TimeUnit timeoutUnit, int timeoutDuration) throws TimeoutException, InterruptedException
    {
        messageQueue.awaitEventCount(expectedMessageCount,timeoutDuration,timeoutUnit);
    }

    public void clear()
    {
        messageQueue.clear();
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
        LOG.debug("onWebSocketClose({},{})",statusCode,reason);
        super.onWebSocketClose(statusCode,reason);
        closeCode = statusCode;
        closeMessage.append(reason);
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketConnect(Session session)
    {
        super.onWebSocketConnect(session);
        openLatch.countDown();
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        LOG.debug("onWebSocketError",cause);
        Assert.assertThat("Error capture",errorQueue.offer(cause),is(true));
    }

    @Override
    public void onWebSocketText(String message)
    {
        LOG.debug("onWebSocketText({})",message);
        messageQueue.offer(message);
        dataLatch.countDown();

        if (messageExchanger != null)
        {
            try
            {
                messageExchanger.exchange(message);
            }
            catch (InterruptedException e)
            {
                LOG.debug(e);
            }
        }
    }

    public void waitForClose(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        Assert.assertThat("Client Socket Closed",closeLatch.await(timeoutDuration,timeoutUnit),is(true));
    }

    public void waitForConnected(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        Assert.assertThat("Client Socket Connected",openLatch.await(timeoutDuration,timeoutUnit),is(true));
    }

    public void waitForMessage(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        LOG.debug("Waiting for message");
        Assert.assertThat("Message Received",dataLatch.await(timeoutDuration,timeoutUnit),is(true));
    }

    public void close()
    {
        getSession().close();
    }
}
