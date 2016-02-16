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

package org.eclipse.jetty.websocket.jsr356.server;

import javax.websocket.server.PathParam;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrParamId;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrCallable;
import org.eclipse.jetty.websocket.jsr356.annotations.Param;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

/**
 * Param handling for static parameters annotated with &#064;{@link PathParam}
 */
public class JsrPathParamId implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrPathParamId();

    @Override
    public boolean process(Param param, JsrCallable callable) throws InvalidSignatureException
    {
        PathParam pathparam = param.getAnnotation(PathParam.class);
        if(pathparam != null)
        {
            param.bind(Role.PATH_PARAM);
            param.setPathParamName(pathparam.value());
            return true;
        }

        return false;
    }
}
