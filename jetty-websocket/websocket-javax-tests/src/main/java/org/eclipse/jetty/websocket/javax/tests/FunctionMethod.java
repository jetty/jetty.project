//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Utility to obtain the {@link Function#apply(Object)} method as a {@link MethodHandle}
 */
public class FunctionMethod
{
    private static final Method functionApplyMethod;
    private static final MethodHandle functionApplyMethodHandle;

    static
    {
        Method foundMethod = null;

        for (Method method : Function.class.getDeclaredMethods())
        {
            if (method.getName().equals("apply") && method.getParameterCount() == 1)
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
