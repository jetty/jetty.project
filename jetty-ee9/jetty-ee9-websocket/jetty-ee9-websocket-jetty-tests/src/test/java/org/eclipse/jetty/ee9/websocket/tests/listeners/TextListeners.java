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
import java.io.Reader;
import java.util.stream.Stream;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketListener;
import org.eclipse.jetty.ee9.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.provider.Arguments;

public class TextListeners
{
    public static Stream<Arguments> getTextListeners()
    {
        return Stream.of(
            StringWholeListener.class,
            StringPartialListener.class,
            AnnotatedStringWholeListener.class,
            AnnotatedReaderWholeListener.class,
            AnnotatedReverseArgumentPartialListener.class
        ).map(Arguments::of);
    }

    public static class StringWholeListener extends AbstractListener implements WebSocketListener
    {
        @Override
        public void onWebSocketText(String message)
        {
            sendText(message, true);
        }
    }

    public static class StringPartialListener extends AbstractListener implements WebSocketPartialListener
    {
        @Override
        public void onWebSocketPartialText(String message, boolean fin)
        {
            sendText(message, fin);
        }
    }

    @WebSocket
    public static class AnnotatedStringWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(String message)
        {
            sendText(message, true);
        }
    }

    @WebSocket
    public static class AnnotatedReaderWholeListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(Reader reader)
        {
            sendText(readString(reader), true);
        }
    }

    @WebSocket
    public static class AnnotatedReverseArgumentPartialListener extends AbstractAnnotatedListener
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            sendText(message, true);
        }
    }

    public static String readString(Reader reader)
    {
        try
        {
            return IO.toString(reader);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}
