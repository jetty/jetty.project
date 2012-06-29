package org.eclipse.jetty.websocket.api;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class EventMethod
{
    public static final EventMethod NOOP = new EventMethod();
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

    public static EventMethod findAnnotatedMethod(Object pojo, Class<? extends Annotation> annoClass, Class<?>... paramTypes)
    {
        Class<?>[] possibleParams = new Class<?>[paramTypes.length];
        System.arraycopy(paramTypes,0,possibleParams,0,possibleParams.length);

        for (Method method : pojo.getClass().getDeclaredMethods())
        {
            if (method.getAnnotation(annoClass) == null)
            {
                // skip, not interested
                continue;
            }

        }
        return NOOP;
    }

    protected Class<?> pojo;
    protected Method method;
    private Class<?>[] paramTypes;

    private EventMethod()
    {
        this.method = null;
    }

    public EventMethod(Class<?> pojo, Method method)
    {
        this.pojo = pojo;
        this.paramTypes = method.getParameterTypes();
        this.method = method;
    }

    public EventMethod(Class<?> pojo, String methodName, Class<?>... paramTypes)
    {
        try
        {
            this.pojo = pojo;
            this.paramTypes = paramTypes;
            this.method = pojo.getMethod(methodName,paramTypes);
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
            String err = String.format("Cannot call method %s on %s with args: %s",method,pojo,args);
            throw new WebSocketException(err,e);
        }
    }

    protected Method getMethod()
    {
        return method;
    }

    protected Class<?>[] getParamTypes()
    {
        return this.paramTypes;
    }

    public boolean isParameterPresent(Class<?> type)
    {
        for (Class<?> param : paramTypes)
        {
            if (param.equals(type))
            {
                return true;
            }
        }
        return false;
    }
}