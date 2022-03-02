//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.tests.listeners;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketListener;
import org.eclipse.jetty.ee9.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.provider.Arguments;

public class BinaryListeners
{
    public static Stream<Arguments> getBinaryListeners()
    {
        return Stream.of(
            OffsetByteArrayWholeListener.class,
            OffsetByteBufferPartialListener.class,
            AnnotatedByteBufferWholeListener.class,
            AnnotatedByteArrayWholeListener.class,
            AnnotatedOffsetByteArrayWholeListener.class,
            AnnotatedInputStreamWholeListener.class,
            AnnotatedReverseArgumentPartialListener.class
        ).map(Arguments::of);
    }

    public static class OffsetByteArrayWholeListener extends AbstractListener implements WebSocketListener
    {
        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
            sendBinary(BufferUtil.toBuffer(payload, offset, len), true);
        }
    }

    public static class OffsetByteBufferPartialListener extends AbstractListener implements WebSocketPartialListener
    {
        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            sendBinary(payload, fin);
        }
    }

    @WebSocket
    public static class AnnotatedByteBufferWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(ByteBuffer message)
        {
            sendBinary(message, true);
        }
    }

    @WebSocket
    public static class AnnotatedByteArrayWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(byte[] message)
        {
            sendBinary(BufferUtil.toBuffer(message), true);
        }
    }

    @WebSocket
    public static class AnnotatedOffsetByteArrayWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(byte[] message, int offset, int length)
        {
            sendBinary(BufferUtil.toBuffer(message, offset, length), true);
        }
    }

    @WebSocket
    public static class AnnotatedInputStreamWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(InputStream stream)
        {
            sendBinary(readBytes(stream), true);
        }
    }

    @WebSocket
    public static class AnnotatedReverseArgumentPartialListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(Session session, ByteBuffer message)
        {
            sendBinary(message, true);
        }
    }

    public static ByteBuffer readBytes(InputStream stream)
    {
        try
        {
            return BufferUtil.toBuffer(IO.readBytes(stream));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}
