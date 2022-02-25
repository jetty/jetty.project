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

package org.eclipse.jetty.websocket.jakarta.tests.matchers;

import java.util.Map;

import jakarta.websocket.Decoder;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.websocket.jakarta.common.RegisteredMessageHandler;
import org.eclipse.jetty.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.websocket.jakarta.tests.MessageType;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

public class IsMessageHandlerTypeRegistered extends TypeSafeMatcher<JakartaWebSocketSession>
{
    private final MessageType expectedType;

    public IsMessageHandlerTypeRegistered(MessageType expectedType)
    {
        this.expectedType = expectedType;
    }

    // Describe Expectation (reason or entries)
    @Override
    public void describeTo(Description description)
    {
        description.appendText(".getMessageHandlers() contains registered MessageHandler for type " + expectedType);
    }

    @Override
    protected boolean matchesSafely(JakartaWebSocketSession session)
    {
        Map<Byte, RegisteredMessageHandler> handlerMap = session.getFrameHandler().getMessageHandlerMap();

        if (handlerMap == null)
        {
            return false;
        }

        for (RegisteredMessageHandler registeredMessageHandler : handlerMap.values())
        {
            Class<?> onMessageType = registeredMessageHandler.getHandlerType();

            RegisteredDecoder registeredDecoder = session.getDecoders().getFirstRegisteredDecoder(onMessageType);
            if (registeredDecoder == null)
            {
                continue;
            }

            if (expectedType == MessageType.PONG)
            {
                if (PongMessage.class.isAssignableFrom(registeredDecoder.objectType))
                    return true;
                continue;
            }

            if (expectedType == MessageType.BINARY)
            {
                if (registeredDecoder.implementsInterface(Decoder.Binary.class))
                    return true;
                if (registeredDecoder.implementsInterface(Decoder.BinaryStream.class))
                    return true;
                continue;
            }

            if (expectedType == MessageType.TEXT)
            {
                if (registeredDecoder.implementsInterface(Decoder.Text.class))
                    return true;
                if (registeredDecoder.implementsInterface(Decoder.TextStream.class))
                    return true;
                continue;
            }
        }

        return false;
    }

    @Override
    protected void describeMismatchSafely(JakartaWebSocketSession session, Description mismatchDescription)
    {
        Map<Byte, RegisteredMessageHandler> handlerMap = session.getFrameHandler().getMessageHandlerMap();

        mismatchDescription.appendText(".getMessageHandlers()");

        if (handlerMap == null)
        {
            mismatchDescription.appendText(" is <null>");
            return;
        }

        mismatchDescription.appendText(" contains [");
        boolean delim = false;
        for (RegisteredMessageHandler registeredMessageHandler : handlerMap.values())
        {
            Class<? extends MessageHandler> handlerClass = registeredMessageHandler.getMessageHandler().getClass();
            if (delim)
            {
                mismatchDescription.appendText(", ");
            }
            delim = true;
            mismatchDescription.appendText(handlerClass.getName());

            Class<?> onMessageType = registeredMessageHandler.getHandlerType();

            if (onMessageType == null)
            {
                mismatchDescription.appendText("<UnknownType>");
                continue;
            }

            mismatchDescription.appendText("<" + onMessageType.getName() + ">");

            RegisteredDecoder registeredDecoder = session.getDecoders().getFirstRegisteredDecoder(onMessageType);
            if (registeredDecoder == null)
            {
                mismatchDescription.appendText("(!NO-DECODER!)");
                continue;
            }

            mismatchDescription.appendText("(" + registeredDecoder.interfaceType.getName() + ")");
        }

        mismatchDescription.appendText("]");
    }

    public static IsMessageHandlerTypeRegistered isMessageHandlerTypeRegistered(MessageType messageType)
    {
        return new IsMessageHandlerTypeRegistered(messageType);
    }
}
