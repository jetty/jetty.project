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

package org.eclipse.jetty.ee9.websocket.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;

import org.eclipse.jetty.ee9.websocket.api.Frame;
import org.eclipse.jetty.ee9.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.ee9.websocket.api.WebSocketListener;
import org.eclipse.jetty.ee9.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.ee9.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.internal.util.TextUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class EndPoints
{
    private EndPoints()
    {
    }

    public static class ListenerBasicSocket implements WebSocketListener
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
            events.add("onWebSocketBinary([%d], %d, %d)", payload.length, offset, len);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            events.add("onWebSocketConnect(%s)", session);
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

    public static class ListenerFrameSocket implements WebSocketFrameListener
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            events.add("onWebSocketConnect(%s)", session);
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            events.add("onWebSocketError((%s) %s)", cause.getClass().getSimpleName(), TextUtils.quote(cause.getMessage()));
        }

        @Override
        public void onWebSocketFrame(Frame frame)
        {
            events.add("onWebSocketFrame(%s)", frame.toString());
        }
    }

    public static class ListenerPartialSocket implements WebSocketPartialListener
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            events.add("onWebSocketConnect(%s)", session);
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
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            events.add("onWebSocketPartialBinary(%s, %b)", BufferUtil.toDetailString(payload), fin);
        }
    }

    public static class ListenerPingPongSocket implements WebSocketPingPongListener
    {
        public EventQueue events = new EventQueue();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            events.add("onWebSocketClose(%s, %s)", CloseStatus.codeString(statusCode), TextUtils.quote(reason));
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            events.add("onWebSocketConnect(%s)", session);
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
        /**
         * First method
         *
         * @param payload the payload
         * @param offset the offset
         * @param len the len
         */
        @OnWebSocketMessage
        public void binMe(byte[] payload, int offset, int len)
        {
            /* ignore */
        }

        /**
         * Second method (also binary)
         *
         * @param stream the input stream
         */
        @OnWebSocketMessage
        public void streamMe(InputStream stream)
        {
            /* ignore */
        }
    }

    @WebSocket
    public static class AnnotatedBinaryArraySocket
    {
        public EventQueue events = new EventQueue();

        @OnWebSocketMessage
        public void onBinary(byte[] payload, int offset, int length)
        {
            events.add("onBinary([%d],%d,%d)", payload.length, offset, length);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            events.add("onClose(%d, %s)", statusCode, TextUtils.quote(reason));
        }

        @OnWebSocketConnect
        public void onConnect(Session sess)
        {
            events.add("onConnect(%s)", sess);
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

        @OnWebSocketConnect
        public void onConnect(Session sess)
        {
            events.add("onConnect(%s)", sess);
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

        @OnWebSocketConnect
        public void onConnect(Session sess)
        {
            events.add("onConnect(%s)", sess);
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

        @OnWebSocketConnect
        public void onConnect(Session sess)
        {
            events.add("onConnect(%s)", sess);
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

    @WebSocket
    public static class FrameSocket
    {
        /**
         * A frame
         *
         * @param frame the frame
         */
        @OnWebSocketFrame
        public void frameMe(Frame frame)
        {
            /* ignore */
        }
    }

    /**
     * Test of constructing a new WebSocket based on a base class
     */
    @WebSocket
    public static class MyEchoBinarySocket extends MyEchoSocket
    {
        @OnWebSocketMessage
        public void echoBin(byte[] buf, int offset, int length)
        {
            try
            {
                getRemote().sendBytes(ByteBuffer.wrap(buf, offset, length));
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
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
        private RemoteEndpoint remote;

        public RemoteEndpoint getRemote()
        {
            return remote;
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.session = null;
        }

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            this.session = session;
            this.remote = session.getRemote();
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

            try
            {
                remote.sendString(message);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
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
            session.getRemote().sendString(text, null);
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
        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            /* do nothing */
        }
    }
}
