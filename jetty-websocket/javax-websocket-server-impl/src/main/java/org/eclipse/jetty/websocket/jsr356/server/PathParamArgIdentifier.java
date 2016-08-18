//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.ArgIdentifier;

/**
 * Identify method parameters tagged with &#064;{@link javax.websocket.server.PathParam}
 */
@SuppressWarnings("unused")
public class PathParamArgIdentifier implements ArgIdentifier
{
    @Override
    public Arg apply(Arg arg)
    {
        PathParam param = arg.getAnnotation(PathParam.class);
        if (param != null)
            arg.setTag(param.value());
        return arg;
    }
}
