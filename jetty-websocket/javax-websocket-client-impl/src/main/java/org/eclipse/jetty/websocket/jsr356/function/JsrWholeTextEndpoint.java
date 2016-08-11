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

package org.eclipse.jetty.websocket.jsr356.function;

import java.lang.reflect.Method;

import javax.websocket.Decoder;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;

/**
 * Possible Text Endpoint Functions
 */
public class JsrWholeTextEndpoint
{
    private final Arg SESSION = new Arg(Session.class);
    private DynamicArgs.Builder ARGBUILDER;
    
    public JsrWholeTextEndpoint(JsrEndpointFunctions jsrFunctions)
    {
        ARGBUILDER = new DynamicArgs.Builder();
        
        jsrFunctions.getAvailableDecoders().supporting(Decoder.Text.class)
                .forEach(registeredDecoder ->
                        ARGBUILDER.addSignature(
                                jsrFunctions.createCallArgs(
                                        SESSION, new Arg(registeredDecoder.objectType).required()
                                )
                        )
                );
    }
    
    public DynamicArgs.Signature getMatchingSignature(Method onMsg)
    {
        return ARGBUILDER.getMatchingSignature(onMsg);
    }
}
