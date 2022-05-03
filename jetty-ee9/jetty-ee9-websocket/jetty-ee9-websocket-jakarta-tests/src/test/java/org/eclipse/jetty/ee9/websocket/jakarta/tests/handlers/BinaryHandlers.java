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

package org.eclipse.jetty.websocket.jakarta.tests.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.provider.Arguments;

public class BinaryHandlers
{
    public static Stream<Arguments> getBinaryHandlers()
    {
        return Stream.of(
            ByteArrayWholeHandler.class,
            ByteArrayPartialHandler.class,
            ByteBufferWholeHandler.class,
            ByteBufferPartialHandler.class,
            InputStreamWholeHandler.class,
            AnnotatedByteBufferWholeHandler.class,
            AnnotatedByteBufferPartialHandler.class,
            AnnotatedByteArrayWholeHandler.class,
            AnnotatedByteArrayPartialHandler.class,
            AnnotatedInputStreamWholeHandler.class,
            AnnotatedReverseArgumentPartialHandler.class
        ).map(Arguments::of);
    }

    public static class ByteArrayWholeHandler extends AbstractHandler implements MessageHandler.Whole<byte[]>
    {
        @Override
        public void onMessage(byte[] message)
        {
            sendBinary(BufferUtil.toBuffer(message), true);
        }
    }

    public static class ByteArrayPartialHandler extends AbstractHandler implements MessageHandler.Partial<byte[]>
    {
        @Override
        public void onMessage(byte[] partialMessage, boolean last)
        {
            sendBinary(BufferUtil.toBuffer(partialMessage), last);
        }
    }

    public static class ByteBufferWholeHandler extends AbstractHandler implements MessageHandler.Whole<ByteBuffer>
    {
        @Override
        public void onMessage(ByteBuffer message)
        {
            sendBinary(message, true);
        }
    }

    public static class ByteBufferPartialHandler extends AbstractHandler implements MessageHandler.Partial<ByteBuffer>
    {
        @Override
        public void onMessage(ByteBuffer partialMessage, boolean last)
        {
            sendBinary(partialMessage, last);
        }
    }

    public static class InputStreamWholeHandler extends AbstractHandler implements MessageHandler.Whole<InputStream>
    {
        @Override
        public void onMessage(InputStream stream)
        {
            sendBinary(readBytes(stream), true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedByteBufferWholeHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(ByteBuffer message)
        {
            sendBinary(message, true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedByteBufferPartialHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(ByteBuffer message, boolean last)
        {
            sendBinary(message, last);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedByteArrayWholeHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(byte[] message)
        {
            sendBinary(BufferUtil.toBuffer(message), true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedByteArrayPartialHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(byte[] message, boolean last)
        {
            sendBinary(BufferUtil.toBuffer(message), last);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedInputStreamWholeHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(InputStream stream)
        {
            sendBinary(readBytes(stream), true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedReverseArgumentPartialHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(boolean last, Session session, byte[] message)
        {
            sendBinary(BufferUtil.toBuffer(message), last);
        }
    }

    private static ByteBuffer readBytes(InputStream stream)
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
