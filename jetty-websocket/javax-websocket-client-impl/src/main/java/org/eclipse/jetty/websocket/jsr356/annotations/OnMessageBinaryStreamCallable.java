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

//import java.io.IOException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Callable for {@link OnMessage} annotated methods for {@link InputStream} based binary message objects
 *
 * @see javax.websocket.Decoder.BinaryStream
 */
public class OnMessageBinaryStreamCallable extends OnMessageCallable
{
    private Decoder.BinaryStream<?> binaryDecoder;

    public OnMessageBinaryStreamCallable(Class<?> pojo, Method method)
    {
        super(pojo, method);
    }

    /**
     * Copy Constructor
     *
     * @param copy the callable to copy from
     */
    public OnMessageBinaryStreamCallable(OnMessageCallable copy)
    {
        super(copy);
    }

    public Object call(Object endpoint, InputStream stream) throws DecodeException, IOException
    {
        // Bug-430088 - streaming based calls are dispatched.
        // create a copy of the calling args array to prevent concurrency problems.
        Object[] copy = new Object[super.args.length];
        System.arraycopy(super.args, 0, copy, 0, super.args.length);
        copy[idxMessageObject] = binaryDecoder.decode(stream);
        return super.call(endpoint, copy);
    }

    @Override
    public void init(JsrSession session)
    {
        idxMessageObject = findIndexForRole(Role.MESSAGE_BINARY_STREAM);
        assertRoleRequired(idxMessageObject, "Binary InputStream Message Object");
        super.init(session);
        assertDecoderRequired();
        binaryDecoder = (Decoder.BinaryStream<?>)getDecoder();
    }
}
