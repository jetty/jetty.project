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

import java.util.Set;

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

public class IsMessageHandlerTypeRegistered extends TypeSafeMatcher<JavaxWebSocketSession>
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
    protected boolean matchesSafely(JavaxWebSocketSession session)
    {
        Set<MessageHandler> handlerSet = session.getMessageHandlers();

        if (handlerSet == null)
        {
            return false;
        }

        for (MessageHandler messageHandler : handlerSet)
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
                continue;
            }

            AvailableDecoders.RegisteredDecoder registeredDecoder = session.getDecoders().getRegisteredDecoderFor(onMessageClass);
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
                if (Decoder.Binary.class.isAssignableFrom(registeredDecoder.interfaceType))
                    return true;
                if (Decoder.BinaryStream.class.isAssignableFrom(registeredDecoder.interfaceType))
                    return true;
                continue;
            }

            if (expectedType == MessageType.TEXT)
            {
                if (Decoder.Text.class.isAssignableFrom(registeredDecoder.interfaceType))
                    return true;
                if (Decoder.TextStream.class.isAssignableFrom(registeredDecoder.interfaceType))
                    return true;
                continue;
            }
        }

        return false;
    }

    @Override
    protected void describeMismatchSafely(JavaxWebSocketSession session, Description mismatchDescription)
    {
        Set<MessageHandler> handlerSet = session.getMessageHandlers();

        mismatchDescription.appendText(".getMessageHandlers()");

        if (handlerSet == null)
        {
            mismatchDescription.appendText(" is <null>");
            return;
        }

        mismatchDescription.appendText(" contains [");
        boolean delim = false;
        for (MessageHandler messageHandler : handlerSet)
        {
            Class<? extends MessageHandler> handlerClass = messageHandler.getClass();
            if (delim)
            {
                mismatchDescription.appendText(", ");
            }
            delim = true;
            mismatchDescription.appendText(handlerClass.getName());
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
                mismatchDescription.appendText("<UnknownType>");
                continue;
            }

            mismatchDescription.appendText("<" + onMessageClass.getName() + ">");

            AvailableDecoders.RegisteredDecoder registeredDecoder = session.getDecoders().getRegisteredDecoderFor(onMessageClass);
            if (registeredDecoder == null)
            {
                mismatchDescription.appendText("(!NO-DECODER!)");
                continue;
            }

            mismatchDescription.appendText("(" + registeredDecoder.interfaceType.getName() + ")");
        }

        mismatchDescription.appendText("]");
    }

    @Factory
    public static IsMessageHandlerTypeRegistered isMessageHandlerTypeRegistered(MessageType messageType)
    {
        return new IsMessageHandlerTypeRegistered(messageType);
    }
}
