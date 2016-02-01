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

package org.eclipse.jetty.websocket.common.events.annotated;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class EventMethod
{
    private static final Logger LOG = Log.getLogger(EventMethod.class);

    private static Object[] dropFirstArg(Object[] args)
    {
        if (args.length == 1)
        {
            return new Object[0];
        }
        Object ret[] = new Object[args.length - 1];
        System.arraycopy(args,1,ret,0,ret.length);
        return ret;
    }

    protected Class<?> pojo;
    protected Method method;
    private boolean hasSession = false;
    private boolean isStreaming = false;
    private Class<?>[] paramTypes;

    public EventMethod(Class<?> pojo, Method method)
    {
        this.pojo = pojo;
        this.paramTypes = method.getParameterTypes();
        this.method = method;
        identifyPresentParamTypes();
    }

    public EventMethod(Class<?> pojo, String methodName, Class<?>... paramTypes)
    {
        try
        {
            this.pojo = pojo;
            this.paramTypes = paramTypes;
            this.method = pojo.getMethod(methodName,paramTypes);
            identifyPresentParamTypes();
        }
        catch (NoSuchMethodException | SecurityException e)
        {
            LOG.warn("Cannot use method {}({}): {}",methodName,paramTypes,e.getMessage());
            this.method = null;
        }
    }

    public void call(Object obj, Object... args)
    {
        if ((this.pojo == null) || (this.method == null))
        {
            LOG.warn("Cannot execute call: pojo={}, method={}",pojo,method);
            return; // no call event method determined
        }
        if (obj == null)
        {
            LOG.warn("Cannot call {} on null object",this.method);
            return;
        }
        if (args.length > paramTypes.length)
        {
            Object trimArgs[] = dropFirstArg(args);
            call(obj,trimArgs);
            return;
        }
        if (args.length < paramTypes.length)
        {
            throw new IllegalArgumentException("Call arguments length [" + args.length + "] must always be greater than or equal to captured args length ["
                    + paramTypes.length + "]");
        }

        try
        {
            this.method.invoke(obj,args);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
        {
            String err = String.format("Cannot call method %s on %s with args: %s",method,pojo, QuoteUtil.join(args,","));
            throw new WebSocketException(err,e);
        }
    }

    public Method getMethod()
    {
        return method;
    }

    protected Class<?>[] getParamTypes()
    {
        return this.paramTypes;
    }

    private void identifyPresentParamTypes()
    {
        this.hasSession = false;
        this.isStreaming = false;

        if (paramTypes == null)
        {
            return;
        }

        for (Class<?> paramType : paramTypes)
        {
            if (Session.class.isAssignableFrom(paramType))
            {
                this.hasSession = true;
            }
            if (Reader.class.isAssignableFrom(paramType) || InputStream.class.isAssignableFrom(paramType))
            {
                this.isStreaming = true;
            }
        }
    }

    public boolean isHasSession()
    {
        return hasSession;
    }

    public boolean isStreaming()
    {
        return isStreaming;
    }
}
