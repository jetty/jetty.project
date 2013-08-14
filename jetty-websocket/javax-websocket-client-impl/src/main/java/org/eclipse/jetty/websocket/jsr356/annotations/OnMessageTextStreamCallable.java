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

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.Decoder.TextStream;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link OnMessage} annotated methods for {@link Reader} based text message objects
 * 
 * @see TextStream
 */
public class OnMessageTextStreamCallable extends OnMessageCallable
{
    private Decoder.TextStream<?> textDecoder;

    public OnMessageTextStreamCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);
    }

    /**
     * Copy Constructor
     */
    public OnMessageTextStreamCallable(OnMessageCallable copy)
    {
        super(copy);
    }

    public Object call(Object endpoint, Reader reader) throws DecodeException, IOException
    {
        super.args[idxMessageObject] = textDecoder.decode(reader);
        return super.call(endpoint,super.args);
    }

    @Override
    public void init(JsrSession session)
    {
        idxMessageObject = findIndexForRole(Role.MESSAGE_TEXT_STREAM);
        assertRoleRequired(idxMessageObject,"Text Reader Message Object");
        super.init(session);
        assertDecoderRequired();
        textDecoder = (Decoder.TextStream<?>)getDecoder();
    }
}
