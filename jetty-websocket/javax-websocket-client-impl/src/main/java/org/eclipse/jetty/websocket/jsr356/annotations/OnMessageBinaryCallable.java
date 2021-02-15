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

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link javax.websocket.OnMessage} annotated methods with a whole or partial binary messages.
 * <p>
 * Not for use with {@link java.io.InputStream} based {@link javax.websocket.OnMessage} method objects.
 *
 * @see javax.websocket.Decoder.Binary
 */
public class OnMessageBinaryCallable extends OnMessageCallable
{
    private Decoder.Binary<?> binaryDecoder;

    public OnMessageBinaryCallable(Class<?> pojo, Method method)
    {
        super(pojo, method);
    }

    /**
     * Copy Constructor
     *
     * @param copy the callable to copy
     */
    public OnMessageBinaryCallable(OnMessageCallable copy)
    {
        super(copy);
    }

    public Object call(Object endpoint, ByteBuffer buf, boolean partialFlag) throws DecodeException
    {
        if (binaryDecoder.willDecode(buf.slice()))
        {
            super.args[idxMessageObject] = binaryDecoder.decode(buf);
            if (idxPartialMessageFlag >= 0)
            {
                super.args[idxPartialMessageFlag] = partialFlag;
            }
            return super.call(endpoint, super.args);
        }
        else
        {
            // Per JSR356, if you cannot decode, discard the message.
            return null;
        }
    }

    @Override
    public void init(JsrSession session)
    {
        idxMessageObject = findIndexForRole(Role.MESSAGE_BINARY);
        assertRoleRequired(idxMessageObject, "Binary Message Object");
        super.init(session);
        assertDecoderRequired();
        binaryDecoder = (Decoder.Binary<?>)getDecoder();
    }
}
