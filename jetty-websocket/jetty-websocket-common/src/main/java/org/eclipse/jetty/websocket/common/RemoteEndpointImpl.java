//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.core.io.WSRemoteImpl;

public class RemoteEndpointImpl extends WSRemoteImpl implements org.eclipse.jetty.websocket.api.RemoteEndpoint
{
    private final InetSocketAddress remoteAddress;

    public RemoteEndpointImpl(OutgoingFrames outgoingFrames, InetSocketAddress remoteAddress)
    {
        super(outgoingFrames);
        this.remoteAddress = remoteAddress;
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException
    {
        sendBlocking(new BinaryFrame().setPayload(data));
    }

    @Override
    public void sendPartialBinary(ByteBuffer fragment, boolean isLast) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendPartialBinary(fragment, isLast, b);
            b.block();
        }
    }

    @Override
    public void sendPartialText(String fragment, boolean isLast) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendPartialText(fragment, isLast, b);
            b.block();
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        sendPing(applicationData, Callback.NOOP);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        sendPong(applicationData, Callback.NOOP);
    }

    @Override
    public void sendText(String text) throws IOException
    {
        sendBlocking(new TextFrame().setPayload(text));
    }

    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        sendBlocking(new BinaryFrame().setPayload(data));
    }

    @Override
    public Future<Void> sendBytesByFuture(ByteBuffer data)
    {
        return sendFrameByFuture(new BinaryFrame().setPayload(data));
    }

    @Override
    public void sendBytes(ByteBuffer data, WriteCallback callback)
    {
        sendBinary(data, callback);
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        sendPartialBinary(fragment, isLast);
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        sendPartialText(fragment, isLast);
    }

    @Override
    public void sendString(String text) throws IOException
    {
        sendBlocking(new TextFrame().setPayload(text));
    }

    @Override
    public Future<Void> sendStringByFuture(String text)
    {
        return sendFrameByFuture(new TextFrame().setPayload(text));
    }

    @Override
    public void sendString(String text, WriteCallback callback)
    {
        sendText(text, callback);
    }

    @Override
    public InetSocketAddress getInetSocketAddress()
    {
        return remoteAddress;
    }

    private Future<Void> sendFrameByFuture(WSFrame frame)
    {
        lockMsg(MsgType.ASYNC);
        try
        {
            FutureCallback future = new FutureCallback();
            unlockedSendFrame(frame, future);
            return future;
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }
}
