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

package org.eclipse.jetty.websocket.jsr356;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.Function;

public class FunctionMethod
{
    private static final Method functionApplyMethod;
    private static final MethodHandle functionApplyMethodHandle;

    static
    {
        Method foundMethod = null;

        for(Method method: Function.class.getDeclaredMethods())
        {
            if(method.getName().equals("apply") && method.getParameterCount() == 1)
            {
                foundMethod = method;
                break;
            }
        }

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        functionApplyMethod = foundMethod;
        try
        {
            functionApplyMethodHandle = lookup.unreflect(functionApplyMethod);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Unable to access: " + functionApplyMethod, e);
        }
    }

    public static MethodHandle getFunctionApplyMethodHandle()
    {
        return functionApplyMethodHandle;
    }
}
