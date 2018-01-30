//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.matchers;

import javax.websocket.Decoder;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.tests.MessageType;
import org.eclipse.jetty.websocket.jsr356.util.ReflectUtils;
import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeMatcher;

public class IsMessageHandlerType extends TypeSafeMatcher<MessageHandler>
{
    private final JavaxWebSocketSession session;
    private final MessageType expectedType;

    public IsMessageHandlerType(JavaxWebSocketSession session, MessageType expectedType)
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

        AvailableDecoders.RegisteredDecoder registeredDecoder = session.getDecoders().getRegisteredDecoderFor(onMessageClass);
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
        }

        return false;
    }

    @Factory
    public static IsMessageHandlerType isMessageHandlerType(JavaxWebSocketSession session, MessageType messageType)
    {
        return new IsMessageHandlerType(session, messageType);
    }
}
