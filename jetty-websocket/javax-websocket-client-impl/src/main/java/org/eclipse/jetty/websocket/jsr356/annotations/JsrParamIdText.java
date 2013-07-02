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

import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;
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
    public boolean process(Param param, JsrCallable callable) throws InvalidSignatureException
    {
        // Session parameter (optional)
        if (param.type.isAssignableFrom(Session.class))
        {
            param.bind(Role.SESSION);
            return true;
        }

        // String for whole message
        if (param.type.isAssignableFrom(String.class))
        {
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(StringDecoder.class);
            return true;
        }

        // Java primitive or class equivalent to receive the whole message converted to that type
        if (param.type.isAssignableFrom(Boolean.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(BooleanDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Byte.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(ByteDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Character.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(CharacterDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Double.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(DoubleDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Float.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(FloatDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Integer.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(IntegerDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Long.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(LongDecoder.class);
            return true;
        }
        if (param.type.isAssignableFrom(Short.class))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecoderClass(ShortDecoder.class);
            return true;
        }

        // Boolean (for indicating partial message support)
        if (param.type.isAssignableFrom(Boolean.TYPE))
        {
            param.bind(Role.MESSAGE_PARTIAL_FLAG);
            return true;
        }

        return false;
    }
}
