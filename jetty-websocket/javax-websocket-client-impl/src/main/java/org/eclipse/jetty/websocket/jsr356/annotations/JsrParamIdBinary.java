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

import java.nio.ByteBuffer;

import javax.websocket.Decoder;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrMethod.MessageType;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteArrayDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.ByteBufferDecoder;

/**
 * Param handling for static Binary &#064;{@link OnMessage} parameters.
 */
public class JsrParamIdBinary extends JsrParamIdOnMessage implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamIdBinary();

    @Override
    public boolean process(Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException
    {
        if (type.isAssignableFrom(ByteBuffer.class))
        {
            method.setMessageType(MessageType.BINARY);
            method.setMessageDecoder(ByteBufferDecoder.class);
            return true;
        }

        if (type.isAssignableFrom(byte[].class))
        {
            method.setMessageType(MessageType.BINARY);
            method.setMessageDecoder(ByteArrayDecoder.class);
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
                if (decoder.isAssignableFrom(ByteBufferDecoder.class) || decoder.isAssignableFrom(ByteArrayDecoder.class))
                {
                    method.enablePartialMessageSupport();
                }
                else
                {
                    StringBuilder err = new StringBuilder();
                    err.append("Unable to support boolean <");
                    err.append(type.getName()).append("> as partial message indicator ");
                    err.append("for a binary message parameter that is not a ");
                    err.append(ByteBuffer.class.getName()).append(" or byte[]");
                    throw new InvalidSignatureException(err.toString());
                }
            }
            return true;
        }
        return false;
    }
}
