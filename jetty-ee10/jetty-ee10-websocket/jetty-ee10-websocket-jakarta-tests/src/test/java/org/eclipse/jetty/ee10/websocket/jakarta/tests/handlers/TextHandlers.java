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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.handlers;

import java.io.IOException;
import java.io.Reader;
import java.util.stream.Stream;

import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.provider.Arguments;

public class TextHandlers
{
    public static Stream<Arguments> getTextHandlers()
    {
        return Stream.of(
            StringWholeHandler.class,
            StringPartialHandler.class,
            ReaderWholeHandler.class,
            AnnotatedStringWholeHandler.class,
            AnnotatedStringPartialHandler.class,
            AnnotatedReaderHandler.class,
            AnnotatedReverseArgumentsPartialHandler.class
        ).map(Arguments::of);
    }

    public static class StringWholeHandler extends AbstractHandler implements MessageHandler.Whole<String>
    {
        @Override
        public void onMessage(String message)
        {
            sendText(message, true);
        }
    }

    public static class StringPartialHandler extends AbstractHandler implements MessageHandler.Partial<String>
    {
        @Override
        public void onMessage(String partialMessage, boolean last)
        {
            sendText(partialMessage, last);
        }
    }

    public static class ReaderWholeHandler extends AbstractHandler implements MessageHandler.Whole<Reader>
    {
        @Override
        public void onMessage(Reader reader)
        {
            sendText(readString(reader), true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedStringWholeHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(String message)
        {
            sendText(message, true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedStringPartialHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(String message, boolean last)
        {
            sendText(message, last);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedReaderHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(Reader reader)
        {
            sendText(readString(reader), true);
        }
    }

    @ServerEndpoint("/")
    public static class AnnotatedReverseArgumentsPartialHandler extends AbstractAnnotatedHandler
    {
        @OnMessage
        public void onMessage(boolean last, String message, Session session)
        {
            sendText(message, last);
        }
    }

    private static String readString(Reader reader)
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
