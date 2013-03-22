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

import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrMethod.MessageType;
import org.eclipse.jetty.websocket.jsr356.decoders.PongMessageDecoder;

public class JsrParamIdPong extends JsrParamIdOnMessage implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamIdPong();

    @Override
    public boolean process(Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException
    {
        if (type.isAssignableFrom(PongMessage.class))
        {
            assertPartialMessageSupportDisabled(type,method);
            method.setMessageType(MessageType.PONG);
            method.setMessageDecoder(PongMessageDecoder.class);
            return true;
        }
        return false;
    }
}
