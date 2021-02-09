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
import java.util.Date;

import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class JsrParamIdDecoderTest
{
    private JsrCallable getOnMessageCallableFrom(Class<?> clazz, String methodName)
    {
        for (Method method : clazz.getMethods())
        {
            if (method.getName().equals(methodName))
            {
                return new OnMessageCallable(clazz, method);
            }
        }
        return null;
    }

    @Test
    public void testMatchDateDecoder()
    {
        DecoderMetadata metadata = new DecoderMetadata(DateDecoder.class, Date.class, MessageType.TEXT, false);
        JsrParamIdDecoder paramId = new JsrParamIdDecoder(metadata);

        JsrCallable callable = getOnMessageCallableFrom(DateTextSocket.class, "onMessage");
        Param param = new Param(0, Date.class, null);

        assertThat("Match for Decoder", paramId.process(param, callable), is(true));
    }
}
