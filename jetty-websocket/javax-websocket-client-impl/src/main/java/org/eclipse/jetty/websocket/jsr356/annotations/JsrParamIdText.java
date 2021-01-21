//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Reader;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Param handling for static Text &#064;{@link javax.websocket.OnMessage} parameters
 */
public class JsrParamIdText extends JsrParamIdOnMessage implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamIdText();

    private boolean isMessageRoleAssigned(JsrCallable callable)
    {
        if (callable instanceof OnMessageCallable)
        {
            OnMessageCallable onmessage = (OnMessageCallable)callable;
            return onmessage.isMessageRoleAssigned();
        }
        return false;
    }

    @Override
    public boolean process(Param param, JsrCallable callable) throws InvalidSignatureException
    {
        if (super.process(param, callable))
        {
            // Found common roles
            return true;
        }

        // String for whole message
        if (param.type.isAssignableFrom(String.class))
        {
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(String.class);
            return true;
        }

        // Java primitive or class equivalent to receive the whole message converted to that type
        if (param.type.isAssignableFrom(Boolean.class))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Boolean.class);
            return true;
        }
        if (param.type.isAssignableFrom(Byte.class) || (param.type == Byte.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Byte.class);
            return true;
        }
        if (param.type.isAssignableFrom(Character.class) || (param.type == Character.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Character.class);
            return true;
        }
        if (param.type.isAssignableFrom(Double.class) || (param.type == Double.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Double.class);
            return true;
        }
        if (param.type.isAssignableFrom(Float.class) || (param.type == Float.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Float.class);
            return true;
        }
        if (param.type.isAssignableFrom(Integer.class) || (param.type == Integer.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Integer.class);
            return true;
        }
        if (param.type.isAssignableFrom(Long.class) || (param.type == Long.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Long.class);
            return true;
        }
        if (param.type.isAssignableFrom(Short.class) || (param.type == Short.TYPE))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT);
            callable.setDecodingType(Short.class);
            return true;
        }

        // Streaming
        if (param.type.isAssignableFrom(Reader.class))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_TEXT_STREAM);
            callable.setDecodingType(Reader.class);
            return true;
        }

        /*
         * boolean primitive.
         *
         * can be used for either: 1) a boolean message type 2) a partial message indicator flag
         */
        if (param.type == Boolean.TYPE)
        {
            if (isMessageRoleAssigned(callable))
            {
                param.bind(Role.MESSAGE_PARTIAL_FLAG);
            }
            else
            {
                param.bind(Role.MESSAGE_TEXT);
                callable.setDecodingType(Boolean.class);
            }
            return true;
        }

        return false;
    }
}
