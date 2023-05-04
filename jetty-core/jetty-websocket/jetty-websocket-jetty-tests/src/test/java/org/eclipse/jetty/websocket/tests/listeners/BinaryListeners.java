//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.listeners;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.junit.jupiter.params.provider.Arguments;

public class BinaryListeners
{
    public static Stream<Arguments> getBinaryListeners()
    {
        return Stream.of(
            OffsetByteArrayWholeListener.class,
            OffsetByteBufferPartialListener.class,
            AnnotatedByteBufferWholeListener.class,
            AnnotatedInputStreamWholeListener.class,
            AnnotatedReverseArgumentPartialListener.class
        ).map(Arguments::of);
    }

    public static class OffsetByteArrayWholeListener extends Session.Listener.Abstract
    {
        @Override
        public void onWebSocketOpen(Session session)
        {
            super.onWebSocketOpen(session);
            session.demand();
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback)
        {
            getSession().sendPartialBinary(payload, true, Callback.from(() ->
            {
                callback.succeed();
                getSession().demand();
            }, callback::fail));
        }
    }

    public static class OffsetByteBufferPartialListener extends Session.Listener.Abstract
    {
        @Override
        public void onWebSocketOpen(Session session)
        {
            super.onWebSocketOpen(session);
            session.demand();
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            getSession().sendPartialBinary(payload, fin, Callback.from(() ->
            {
                callback.succeed();
                getSession().demand();
            }, callback::fail));
        }
    }

    @WebSocket
    public static class AnnotatedByteBufferWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(ByteBuffer message, Callback callback)
        {
            _session.sendPartialBinary(message, true, callback);
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
        public void onMessage(Session session, ByteBuffer message, Callback callback)
        {
            _session.sendPartialBinary(message, true, callback);
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
