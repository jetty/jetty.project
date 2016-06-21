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

import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;

/**
 * Param handling for Text or Binary &#064;{@link javax.websocket.OnMessage} parameters declared as {@link javax.websocket.Decoder}s
 */
public class JsrParamIdDecoder extends JsrParamIdOnMessage implements IJsrParamId
{
    private final DecoderMetadata metadata;

    public JsrParamIdDecoder(DecoderMetadata metadata)
    {
        this.metadata = metadata;
    }

    @Override
    public boolean process(Param param, JsrCallable callable) throws InvalidSignatureException
    {
        if (param.type.isAssignableFrom(metadata.getObjectType()))
        {
            assertPartialMessageSupportDisabled(param,callable);

            switch (metadata.getMessageType())
            {
                case TEXT:
                    if (metadata.isStreamed())
                    {
                        param.bind(Role.MESSAGE_TEXT_STREAM);
                    }
                    else
                    {
                        param.bind(Role.MESSAGE_TEXT);
                    }
                    break;
                case BINARY:
                    if (metadata.isStreamed())
                    {
                        param.bind(Role.MESSAGE_BINARY_STREAM);
                    }
                    else
                    {
                        param.bind(Role.MESSAGE_BINARY);
                    }
                    break;
                case PONG:
                    param.bind(Role.MESSAGE_PONG);
                    break;
            }

            callable.setDecodingType(metadata.getObjectType());
            return true;
        }
        return false;
    }
}
