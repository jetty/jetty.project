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

package org.eclipse.jetty.websocket.jsr356.server;

import javax.websocket.server.PathParam;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrMethod;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrParamId;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrMetadata;

/**
 * Param handling for static parameters annotated with &#064;{@link PathParam}
 */
public class JsrParamPath implements IJsrParamId
{
    public static final IJsrParamId INSTANCE = new JsrParamPath();

    @Override
    public boolean process(Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException
    {
        PathParam pathparam = type.getAnnotation(PathParam.class);

        return false;
    }
}
