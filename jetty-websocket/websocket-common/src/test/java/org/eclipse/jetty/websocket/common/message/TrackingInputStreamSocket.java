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

package org.eclipse.jetty.websocket.common.message;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.junit.Assert;

@WebSocket
public class TrackingInputStreamSocket
{
    private static final Logger LOG = Log.getLogger(TrackingInputStreamSocket.class);
    private final String id;
    public int closeCode = -1;
    public StringBuilder closeMessage = new StringBuilder();
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public EventQueue<String> messageQueue = new EventQueue<>();
    public EventQueue<Throwable> errorQueue = new EventQueue<>();

    public TrackingInputStreamSocket()
    {
        this("socket");
    }

    public TrackingInputStreamSocket(String id)
    {
        this.id = id;
    }

    public void assertClose(int expectedStatusCode, String expectedReason) throws InterruptedException
    {
        assertCloseCode(expectedStatusCode);
        assertCloseReason(expectedReason);
    }

    public void assertCloseCode(int expectedCode) throws InterruptedException
    {
        Assert.assertThat("Was Closed",closeLatch.await(50,TimeUnit.MILLISECONDS),is(true));
        Assert.assertThat("Close Code",closeCode,is(expectedCode));
    }

    private void assertCloseReason(String expectedReason)
    {
        Assert.assertThat("Close Reason",closeMessage.toString(),is(expectedReason));
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onClose({},{})",id,statusCode,reason);
        closeCode = statusCode;
        closeMessage.append(reason);
        closeLatch.countDown();
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        errorQueue.add(cause);
    }

    @OnWebSocketMessage
    public void onInputStream(InputStream stream)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onInputStream({})",id,stream);
        try
        {
            String msg = IO.toString(stream);
            messageQueue.add(msg);
        }
        catch (IOException e)
        {
            errorQueue.add(e);
        }
    }

    public void waitForClose(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        Assert.assertThat("Client Socket Closed",closeLatch.await(timeoutDuration,timeoutUnit),is(true));
    }
}
