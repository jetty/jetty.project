//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.lang.reflect.Method;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link javax.websocket.OnMessage} annotated methods with a whole or partial text messages.
 * <p>
 * Not for use with {@link java.io.Reader} based {@link javax.websocket.OnMessage} method objects.
 * 
 * @see javax.websocket.Decoder.Text
 */
public class OnMessageTextCallable extends OnMessageCallable
{
    private Decoder.Text<?> textDecoder;

    public OnMessageTextCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);
    }

    /**
     * Copy Constructor
     * @param copy the callable to copy from
     */
    public OnMessageTextCallable(OnMessageCallable copy)
    {
        super(copy);
    }

    public Object call(Object endpoint, String str, boolean partialFlag) throws DecodeException
    {
        super.args[idxMessageObject] = textDecoder.decode(str);
        if (idxPartialMessageFlag >= 0)
        {
            super.args[idxPartialMessageFlag] = partialFlag;
        }
        return super.call(endpoint,super.args);
    }

    @Override
    public void init(JsrSession session)
    {
        idxMessageObject = findIndexForRole(Role.MESSAGE_TEXT);
        assertRoleRequired(idxMessageObject,"Text Message Object");
        super.init(session);
        assertDecoderRequired();
        textDecoder = (Decoder.Text<?>)getDecoder();
    }
}
