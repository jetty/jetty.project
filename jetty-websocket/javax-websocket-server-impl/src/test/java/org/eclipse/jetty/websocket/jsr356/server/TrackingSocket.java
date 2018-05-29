//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;

/**
 * Abstract base socket used for tracking state and events within the socket for testing reasons.
 */
public abstract class TrackingSocket
{
    private static final Logger LOG = Log.getLogger(TrackingSocket.class);
    
    public CloseReason closeReason;
    public LinkedBlockingQueue<String> eventQueue = new LinkedBlockingQueue<>();
    public LinkedBlockingQueue<Throwable> errorQueue = new LinkedBlockingQueue<>();
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch dataLatch = new CountDownLatch(1);
    
    protected void addError(Throwable t)
    {
        LOG.warn(t.getClass() + ": " + t.getMessage());

        if(LOG.isDebugEnabled())
            LOG.debug(t);

        errorQueue.offer(t);
    }

    protected void addEvent(String format, Object... args)
    {
        eventQueue.offer(String.format(format,args));
    }

    public void assertClose(CloseCode expectedCode, String expectedReason) throws InterruptedException
    {
        assertCloseCode(expectedCode);
        assertCloseReason(expectedReason);
    }

    public void assertCloseCode(CloseCode expectedCode) throws InterruptedException
    {
        Assert.assertThat("Was Closed",closeLatch.await(50,TimeUnit.MILLISECONDS),is(true));
        Assert.assertThat("CloseReason",closeReason,notNullValue());
        Assert.assertThat("Close Code",closeReason.getCloseCode(),is(expectedCode));
    }

    private void assertCloseReason(String expectedReason)
    {
        Assert.assertThat("Close Reason",closeReason.getReasonPhrase(),is(expectedReason));
    }

    public void assertIsOpen() throws InterruptedException
    {
        assertWasOpened();
        assertNotClosed();
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
        Assert.assertThat("Was Opened",openLatch.await(30000,TimeUnit.MILLISECONDS),is(true));
    }

    public void clear()
    {
        eventQueue.clear();
        errorQueue.clear();
    }

    public void waitForClose(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        Assert.assertThat("Client Socket Closed",closeLatch.await(timeoutDuration,timeoutUnit),is(true));
    }

    public void waitForConnected(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        Assert.assertThat("Client Socket Connected",openLatch.await(timeoutDuration,timeoutUnit),is(true));
    }

    public void waitForData(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Waiting for message");
        Assert.assertThat("Data Received",dataLatch.await(timeoutDuration,timeoutUnit),is(true));
    }
}
