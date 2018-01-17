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

package org.eclipse.jetty.websocket.core.autobahn.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class EchoHandler extends AbstractClientFrameHandler
{
    private final int currentCaseId;
    private CountDownLatch latch = new CountDownLatch(1);

    public EchoHandler(int currentCaseId)
    {
        this.currentCaseId = currentCaseId;
    }

    public void awaitClose() throws InterruptedException
    {
        latch.await(5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        LOG.info("Executing test case {}",currentCaseId);
    }
    
    int count;

    @Override
    public void onText(Utf8StringBuilder utf8, Callback callback, boolean fin)
    {
        LOG.info("onText {} {} {} {}", count++, utf8.length(),fin, getChannel());
        if (fin)
        {
            getChannel().sendFrame(new TextFrame().setPayload(utf8.toString()),
                    callback,
                    BatchMode.OFF);
        }
        else
        {
            callback.succeeded();
        }
    }

    @Override
    public void onBinary(ByteBuffer payload, Callback callback, boolean fin)
    {        
        LOG.info("onBinary {} {} {}", payload==null?-1:payload.remaining(),fin,getChannel());
        if (fin)
        {       
            BinaryFrame echo = new BinaryFrame();
            if (payload!=null)
                echo.setPayload(payload);
            getChannel().sendFrame(echo,callback,BatchMode.OFF);
        }
        else
        {
            callback.succeeded();
        }
    }

    @Override
    public void onError(Throwable cause)
    {
        if (cause instanceof WebSocketTimeoutException)
            LOG.debug("timeout!");
        else
            LOG.warn("onError",cause);
    }
    

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        LOG.info("onClosed {}",closeStatus);
        super.onClosed(closeStatus);
        latch.countDown();
    }
}
