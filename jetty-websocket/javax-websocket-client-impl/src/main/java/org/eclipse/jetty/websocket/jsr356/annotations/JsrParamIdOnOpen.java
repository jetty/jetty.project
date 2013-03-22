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

import javax.websocket.EndpointConfig;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;

/**
 * Param handling for &#064;{@link OnOpen} parameters.
 */
public class JsrParamIdOnOpen implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamIdOnOpen();

    @Override
    public boolean process(java.lang.Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException
    {
        if (type.isAssignableFrom(EndpointConfig.class) || type.isAssignableFrom(Session.class))
        {
            return true;
        }
        return false;
    }
}
