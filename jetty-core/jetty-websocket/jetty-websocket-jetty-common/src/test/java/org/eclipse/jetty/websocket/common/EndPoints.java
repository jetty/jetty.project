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

package org.eclipse.jetty.websocket.common;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.util.TextUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class EndPoints
{
    private EndPoints()
    {
    }

    public static class ListenerBasicSocket implements Session.Listener.AutoDemanding
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback)
        {
            events.add("onWebSocketBinary([%d])", payload.remaining());
            callback.succeed();
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            events.add("onWebSocketOpen(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            events.add("onWebSocketError((%s) %s)", cause.getClass().getSimpleName(), TextUtils.quote(cause.getMessage()));
        }

        @Override
        public void onWebSocketText(String message)
        {
            events.add("onWebSocketText(%s)", TextUtils.quote(message));
        }
    }

    public static class ListenerFrameSocket implements Session.Listener.AutoDemanding
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            events.add("onWebSocketOpen(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            events.add("onWebSocketError((%s) %s)", cause.getClass().getSimpleName(), TextUtils.quote(cause.getMessage()));
        }

        @Override
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            events.add("onWebSocketFrame(%s)", frame.toString());
            callback.succeed();
        }
    }

    public static class ListenerPartialSocket implements Session.Listener.AutoDemanding
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            events.add("onWebSocketOpen(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            events.add("onWebSocketError((%s) %s)", cause.getClass().getSimpleName(), TextUtils.quote(cause.getMessage()));
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            events.add("onWebSocketPartialText(%s, %b)", TextUtils.quote(payload), fin);
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            events.add("onWebSocketPartialBinary(%s, %b)", BufferUtil.toDetailString(payload), fin);
            callback.succeed();
        }
    }

    public static class ListenerPingPongSocket implements Session.Listener.AutoDemanding
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            events.add("onWebSocketOpen(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            events.add("onWebSocketError((%s) %s)", cause.getClass().getSimpleName(), TextUtils.quote(cause.getMessage()));
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            events.add("onWebSocketPing(%s)", BufferUtil.toDetailString(payload));
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            events.add("onWebSocketPong(%s)", BufferUtil.toDetailString(payload));
        }
    }

    /**
     * Invalid Socket: Annotate 2 methods with interest in Binary Messages.
     */
    @WebSocket
    public static class BadDuplicateBinarySocket
    {
        @OnWebSocketMessage
        public void binMe(ByteBuffer payload, Callback callback)
        {
            callback.succeed();
        }

        @OnWebSocketMessage
        public void streamMe(InputStream stream)
        {
        }
    }

    @WebSocket
    public static class AnnotatedBinaryStreamSocket
    {
        public EventQueue events = new EventQueue();

        @OnWebSocketMessage
        public void onBinary(InputStream stream)
        {
            assertThat("InputStream", stream, notNullValue());
            events.add("onBinary(%s)", stream);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            events.add("onClose(%d, %s)", statusCode, TextUtils.quote(reason));
        }

        @OnWebSocketOpen
        public void onOpen(Session sess)
        {
            events.add("onOpen(%s)", sess);
        }
    }

    @WebSocket
    public static class AnnotatedTextSocket
    {
        public EventQueue events = new EventQueue();

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            events.add("onClose(%d, %s)", statusCode, TextUtils.quote(reason));
        }

        @OnWebSocketOpen
        public void onOpen(Session sess)
        {
            events.add("onOpen(%s)", sess);
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            events.add("onError(%s: %s)", cause.getClass().getSimpleName(), cause.getMessage());
        }

        @OnWebSocketMessage
        public void onText(String message)
        {
            events.add("onText(%s)", TextUtils.quote(message));
        }
    }

    @WebSocket
    public static class AnnotatedTextStreamSocket
    {
        public EventQueue events = new EventQueue();

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            events.add("onClose(%d, %s)", statusCode, TextUtils.quote(reason));
        }

        @OnWebSocketOpen
        public void onOpen(Session sess)
        {
            events.add("onOpen(%s)", sess);
        }

        @OnWebSocketMessage
        public void onText(Reader reader)
        {
            events.add("onText(%s)", reader);
        }
    }

    /**
     * Invalid Socket: Annotate a message interest on a method with a return type.
     */
    @WebSocket
    public static class BadBinarySignatureSocket
    {
        /**
         * Declaring a non-void return type
         *
         * @param session the session
         * @param buf the buffer
         * @param offset the offset
         * @param len the length
         * @return the response boolean
         */
        @OnWebSocketMessage
        public boolean onBinary(Session session, byte[] buf, int offset, int len)
        {
            return false;
        }
    }

    @WebSocket
    public static class BadDuplicateFrameSocket
    {
        /**
         * The get a frame
         *
         * @param frame the frame
         */
        @OnWebSocketFrame
        public void frameMe(org.eclipse.jetty.websocket.core.Frame frame)
        {
            /* ignore */
        }

        /**
         * This is a duplicate frame type (should throw an exception attempting to use)
         *
         * @param frame the frame
         */
        @OnWebSocketFrame
        public void watchMe(org.eclipse.jetty.websocket.core.Frame frame)
        {
            /* ignore */
        }
    }

    /**
     * Invalid Socket: Annotate a message interest on a static method
     */
    @WebSocket
    public static class BadTextSignatureSocket
    {
        /**
         * Declaring a static method
         *
         * @param session the session
         * @param text the text message
         */
        @OnWebSocketMessage
        public static void onText(Session session, String text)
        {
            /* do nothing */
        }
    }

    @WebSocket(autoDemand = false)
    public static class BadAutoDemandWithInputStream
    {
        @OnWebSocketMessage
        public void onMessage(InputStream stream)
        {
        }
    }

    @WebSocket(autoDemand = false)
    public static class BadAutoDemandWithReader
    {
        @OnWebSocketMessage
        public void onMessage(Reader reader)
        {
        }
    }

    @WebSocket
    public static class FrameSocket
    {
        @OnWebSocketFrame
        public void frameMe(Frame frame, Callback callback)
        {
            callback.succeed();
        }
    }

    /**
     * Test of constructing a new WebSocket based on a base class
     */
    @WebSocket
    public static class MyEchoBinarySocket extends MyEchoSocket
    {
        @OnWebSocketMessage
        public void echoBin(ByteBuffer payload, Callback callback)
        {
            getSession().sendBinary(payload, callback);
        }
    }

    /**
     * The most common websocket implementation.
     * <p>
     * This version tracks the connection per socket instance and will
     */
    @WebSocket
    public static class MyEchoSocket
    {
        private Session session;

        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        public Session getSession()
        {
            return session;
        }

        @OnWebSocketMessage
        public void onText(String message)
        {
            if (session == null)
            {
                // no connection, do nothing.
                // this is possible due to async behavior
                return;
            }

            session.sendText(message, Callback.NOOP);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.session = null;
        }
    }

    /**
     * Example of a stateless websocket implementation.
     * <p>
     * Useful for websockets that only reply to incoming requests.
     * <p>
     * Note: that for this style of websocket to be viable on the server side be sure that you only create 1 instance of this socket, as more instances would be
     * wasteful of resources and memory.
     */
    @WebSocket
    public static class MyStatelessEchoSocket
    {
        @OnWebSocketMessage
        public void onText(Session session, String text)
        {
            session.sendText(text, null);
        }
    }

    /**
     * The most basic websocket declaration.
     */
    @WebSocket
    public static class NoopSocket
    {
        /* intentionally do nothing */
    }

    /**
     * (Test Case)
     * <p>
     * Intentionally not specifying the @WebSocket annotation here
     */
    public static class NotASocket
    {
        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            /* do nothing */
        }
    }
}
