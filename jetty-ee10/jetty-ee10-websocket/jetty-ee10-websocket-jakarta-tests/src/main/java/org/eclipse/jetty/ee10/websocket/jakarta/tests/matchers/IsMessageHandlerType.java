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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.matchers;

import jakarta.websocket.Decoder;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.MessageType;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

public class IsMessageHandlerType extends TypeSafeMatcher<MessageHandler>
{
    private final JakartaWebSocketSession session;
    private final MessageType expectedType;

    public IsMessageHandlerType(JakartaWebSocketSession session, MessageType expectedType)
    {
        this.session = session;
        this.expectedType = expectedType;
    }

    @Override
    public void describeTo(Description description)
    {
        description.appendText("supports a ");
        switch (expectedType)
        {
            case BINARY:
            case TEXT:
                description.appendText(expectedType.name()).appendText(" based argument/Decoder");
                break;
            case PONG:
                description.appendText(PongMessage.class.getName()).appendText(" argument");
                break;
            default:
                throw new IllegalStateException(expectedType.toString());
        }
    }

    @Override
    protected boolean matchesSafely(MessageHandler messageHandler)
    {
        Class<? extends MessageHandler> handlerClass = messageHandler.getClass();
        Class<?> onMessageClass = null;

        if (MessageHandler.Whole.class.isAssignableFrom(handlerClass))
        {
            onMessageClass = ReflectUtils.findGenericClassFor(handlerClass, MessageHandler.Whole.class);
        }
        else if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
        {
            onMessageClass = ReflectUtils.findGenericClassFor(handlerClass, MessageHandler.Partial.class);
        }

        if (onMessageClass == null)
        {
            return false;
        }

        RegisteredDecoder registeredDecoder = session.getDecoders().getFirstRegisteredDecoder(onMessageClass);
        if (registeredDecoder == null)
        {
            return false;
        }

        switch (expectedType)
        {
            case PONG:
                return PongMessage.class.isAssignableFrom(registeredDecoder.objectType);
            case BINARY:
                return (Decoder.Binary.class.isAssignableFrom(registeredDecoder.interfaceType) ||
                    Decoder.BinaryStream.class.isAssignableFrom(registeredDecoder.interfaceType));
            case TEXT:
                return (Decoder.Text.class.isAssignableFrom(registeredDecoder.interfaceType) ||
                    Decoder.TextStream.class.isAssignableFrom(registeredDecoder.interfaceType));
            default:
                return false;
        }
    }

    public static IsMessageHandlerType isMessageHandlerType(JakartaWebSocketSession session, MessageType messageType)
    {
        return new IsMessageHandlerType(session, messageType);
    }
}
