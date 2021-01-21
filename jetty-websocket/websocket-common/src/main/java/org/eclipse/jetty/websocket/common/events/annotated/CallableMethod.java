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

package org.eclipse.jetty.websocket.common.events.annotated;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * A Callable Method
 */
public class CallableMethod
{
    private static final Logger LOG = Log.getLogger(CallableMethod.class);
    protected final Class<?> pojo;
    protected final Method method;
    protected Class<?>[] paramTypes;

    public CallableMethod(Class<?> pojo, Method method)
    {
        Objects.requireNonNull(pojo, "Pojo cannot be null");
        Objects.requireNonNull(method, "Method cannot be null");
        this.pojo = pojo;
        this.method = method;
        this.paramTypes = method.getParameterTypes();
    }

    public Object call(Object obj, Object... args)
    {
        if ((this.pojo == null) || (this.method == null))
        {
            LOG.warn("Cannot execute call: pojo={}, method={}", pojo, method);
            return null; // no call event method determined
        }

        if (obj == null)
        {
            String err = String.format("Cannot call %s on null object", this.method);
            LOG.warn(new RuntimeException(err));
            return null;
        }

        if (args.length < paramTypes.length)
        {
            throw new IllegalArgumentException("Call arguments length [" + args.length + "] must always be greater than or equal to captured args length [" + paramTypes.length + "]");
        }

        try
        {
            return this.method.invoke(obj, args);
        }
        catch (Throwable t)
        {
            String err = formatMethodCallError(args);
            throw unwrapRuntimeException(err, t);
        }
    }

    private RuntimeException unwrapRuntimeException(String err, final Throwable t)
    {
        Throwable ret = t;

        while (ret instanceof InvocationTargetException)
        {
            ret = ret.getCause();
        }

        if (ret instanceof RuntimeException)
        {
            return (RuntimeException)ret;
        }

        return new RuntimeException(err, ret);
    }

    public String formatMethodCallError(Object... args)
    {
        StringBuilder err = new StringBuilder();
        err.append("Cannot call method ");
        err.append(ReflectUtils.toString(pojo, method));
        err.append(" with args: [");

        boolean delim = false;
        for (Object arg : args)
        {
            if (delim)
            {
                err.append(", ");
            }
            if (arg == null)
            {
                err.append("<null>");
            }
            else
            {
                err.append(arg.getClass().getName());
            }
            delim = true;
        }
        err.append("]");
        return err.toString();
    }

    public Method getMethod()
    {
        return method;
    }

    public Class<?>[] getParamTypes()
    {
        return paramTypes;
    }

    public Class<?> getPojo()
    {
        return pojo;
    }

    @Override
    public String toString()
    {
        return String.format("%s[pojo=%s,method=%s]",
            this.getClass().getSimpleName(),
            pojo.getName(),
            method.toGenericString());
    }
}
