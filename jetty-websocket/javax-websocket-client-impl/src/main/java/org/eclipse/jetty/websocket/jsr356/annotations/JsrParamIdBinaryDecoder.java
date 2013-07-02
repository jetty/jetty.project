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
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Param handling for Binary &#064;{@link OnMessage} parameters declared as {@link Decoder}s of type {@link Decoder.Binary} or {@link Decoder.BinaryStream}
 */
public class JsrParamIdBinaryDecoder extends JsrParamIdOnMessage implements IJsrParamId
{
    private final Class<? extends Decoder> decoder;
    private final Class<?> supportedType;

    public JsrParamIdBinaryDecoder(Class<? extends Decoder> decoder, Class<?> supportedType)
    {
        this.decoder = decoder;
        this.supportedType = supportedType;
    }

    @Override
    public boolean process(Param param, JsrCallable callable) throws InvalidSignatureException
    {
        if (param.type.isAssignableFrom(supportedType))
        {
            assertPartialMessageSupportDisabled(param,callable);
            param.bind(Role.MESSAGE_BINARY);
            callable.setDecoderClass(decoder);
            return true;
        }
        return false;
    }
}
