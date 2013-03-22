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
import org.eclipse.jetty.websocket.jsr356.decoders.Decoders.DecoderRef;

/**
 * Param handling for Text &#064;{@link OnMessage} parameters declared as {@link Decoder}s of type {@link Decoder.Text} or {@link Decoder.TextStream}
 */
public class JsrParamIdTextDecoder extends JsrParamIdOnMessage implements IJsrParamId
{
    private final DecoderRef ref;

    public JsrParamIdTextDecoder(DecoderRef ref)
    {
        this.ref = ref;
    }

    @Override
    public boolean process(Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException
    {
        if (type.isAssignableFrom(ref.getType()))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.TEXT);
            method.setMessageDecoder(ref.getDecoder());
            return true;
        }
        return false;
    }
}
