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

import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Param handling for static Binary &#064;{@link javax.websocket.OnMessage} parameters.
 */
public class JsrParamIdBinary extends JsrParamIdOnMessage implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamIdBinary();

    @Override
    public boolean process(Param param, JsrCallable callable) throws InvalidSignatureException
    {
        if (super.process(param, callable))
        {
            // Found common roles
            return true;
        }

        if (param.type.isAssignableFrom(ByteBuffer.class))
        {
            param.bind(Role.MESSAGE_BINARY);
            callable.setDecodingType(ByteBuffer.class);
            return true;
        }

        if (param.type.isAssignableFrom(byte[].class))
        {
            param.bind(Role.MESSAGE_BINARY);
            callable.setDecodingType(byte[].class);
            return true;
        }

        // Streaming
        if (param.type.isAssignableFrom(InputStream.class))
        {
            assertPartialMessageSupportDisabled(param, callable);
            param.bind(Role.MESSAGE_BINARY_STREAM);
            callable.setDecodingType(InputStream.class);
            return true;
        }

        return false;
    }
}
