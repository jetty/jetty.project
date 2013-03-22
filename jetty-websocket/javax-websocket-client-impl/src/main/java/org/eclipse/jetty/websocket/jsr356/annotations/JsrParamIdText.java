//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.annotations;

import javax.websocket.Decoder;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrMethod.MessageType;
import org.eclipse.jetty.websocket.jsr356.decoders.BooleanDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.CharacterDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.DoubleDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.FloatDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.IntegerDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.LongDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ShortDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.StringDecoder;

/**
 * Param handling for static Text &#064;{@link OnMessage} parameters
 */
public class JsrParamIdText extends JsrParamIdOnMessage implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamIdText();

    @Override
    public boolean process(Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException
    {
        // String for whole message
        if (type.isAssignableFrom(String.class))
        {
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(StringDecoder.class);
            return true;
        }

        // Java primitive or class equivalent to receive the whole message converted to that type
        if (type.isAssignableFrom(Boolean.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(BooleanDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Byte.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(ByteDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Character.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(CharacterDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Double.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(DoubleDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Float.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(FloatDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Integer.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(IntegerDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Long.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(LongDecoder.class);
            return true;
        }
        if (type.isAssignableFrom(Short.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(ShortDecoder.class);
            return true;
        }

        // Boolean (for indicating partial message support)
        if (type.isAssignableFrom(Boolean.TYPE))
        {
            Class<? extends Decoder> decoder = method.getMessageDecoder();
            if (decoder == null)
            {
                // specific decoder technique not identified yet
                method.enablePartialMessageSupport();
            }
            else
            {
                if (decoder.isAssignableFrom(StringDecoder.class))
                {
                    method.enablePartialMessageSupport();
                }
                else
                {
                    StringBuilder err = new StringBuilder();
                    err.append("Unable to support boolean <");
                    err.append(type.getName()).append("> as partial message indicator ");
                    err.append("in conjunction with a non-String text message parameter.");
                    throw new InvalidSignatureException(err.toString());
                }
            }
            return true;
        }

        return false;
    }
}
