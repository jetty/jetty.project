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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
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
    public void onBinaryFrame(DataFrame frame, Callback callback)
    {
        DataFrame dataFrame;

        if(frame.getOpCode() == OpCode.BINARY)
            dataFrame = new BinaryFrame();
        else if(frame.getOpCode() == OpCode.CONTINUATION)
            dataFrame = new ContinuationFrame();
        else
            throw new RuntimeException("Bad opcode to onBinary(" + frame + ", " + callback + ")");

        dataFrame.setFin(frame.isFin());
        dataFrame.setPayload(copyOf(frame.getPayload()));
        getWebSocketChannel().sendFrame(dataFrame, Callback.NOOP, BatchMode.OFF);
        callback.succeeded(); // TODO: pass argument callback into outgoingFrame instead?
    }

    @Override
    public void onTextFrame(DataFrame frame, Callback callback)
    {
        DataFrame dataFrame;

        if(frame.getOpCode() == OpCode.TEXT)
            dataFrame = new TextFrame();
        else if(frame.getOpCode() == OpCode.CONTINUATION)
            dataFrame = new ContinuationFrame();
        else
            throw new RuntimeException("Bad opcode to onText(" + frame + ", " + callback + ")");

        dataFrame.setFin(frame.isFin());
        dataFrame.setPayload(copyOf(frame.getPayload()));
        getWebSocketChannel().sendFrame(dataFrame, Callback.NOOP, BatchMode.OFF);
        callback.succeeded(); // TODO: pass argument callback into outgoingFrame instead?
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        LOG.info("Executing test case {}",currentCaseId);
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        super.onClosed(closeStatus);
        latch.countDown();
    }
}
