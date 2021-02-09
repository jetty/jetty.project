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
import javax.websocket.OnError;

import org.eclipse.jetty.websocket.jsr356.JsrSession;

/**
 * Callable for {@link OnError} annotated methods
 */
public class OnErrorCallable extends JsrCallable
{
    private int idxThrowable = -1;

    public OnErrorCallable(Class<?> pojo, Method method)
    {
        super(pojo, method);
    }

    public OnErrorCallable(OnErrorCallable copy)
    {
        super(copy);
        this.idxThrowable = copy.idxThrowable;
    }

    public void call(Object endpoint, Throwable cause)
    {
        if (idxThrowable == (-1))
        {
            idxThrowable = findIndexForRole(Param.Role.ERROR_CAUSE);
            assertRoleRequired(idxThrowable, "Throwable");
        }

        if (idxThrowable >= 0)
        {
            super.args[idxThrowable] = cause;
        }
        super.call(endpoint, super.args);
    }

    @Override
    public void init(JsrSession session)
    {
        idxThrowable = findIndexForRole(Param.Role.ERROR_CAUSE);
        assertRoleRequired(idxThrowable, "Throwable");
        super.init(session);
    }

    @Override
    public void setDecodingType(Class<?> decodingType)
    {
        /* ignore, not relevant for onClose */
    }
}
