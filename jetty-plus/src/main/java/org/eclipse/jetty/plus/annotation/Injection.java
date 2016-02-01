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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Locale;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.eclipse.jetty.util.IntrospectionUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Injection
 * <p>
 * Represents the injection of a resource into a target (method or field).
 * The injection is performed by doing an ENC lookup using the jndi
 * name provided, and setting the object obtained on the target.
 */
public class Injection
{
    private static final Logger LOG = Log.getLogger(Injection.class);

    private Class<?> _targetClass;
    private String _jndiName;
    private String _mappingName;
    private Member _target;
    private Class<?> _paramClass;
    private Class<?> _resourceClass;


    public Injection ()
    {
    }


    /**
     * @return the _className
     */
    public Class<?> getTargetClass()
    {
        return _targetClass;
    }

    public Class<?> getParamClass ()
    {
        return _paramClass;
    }

    public Class<?> getResourceClass ()
    {
        return _resourceClass;
    }

    public boolean isField ()
    {
        return (_target != null && _target instanceof Field);
    }

    public boolean isMethod ()
    {
        return (_target != null && _target instanceof Method);
    }

    /**
     * @return the jndiName
     */
    public String getJndiName()
    {
        return _jndiName;
    }
    /**
     * @param jndiName the jndiName to set
     */
    public void setJndiName(String jndiName)
    {
        this._jndiName = jndiName;
    }
    /**
     * @return the mappingName
     */
    public String getMappingName()
    {
        return _mappingName;
    }
    /**
     * @param mappingName the mappingName to set
     */
    public void setMappingName(String mappingName)
    {
        this._mappingName = mappingName;
    }

    /**
     * @return the target
     */
    public Member getTarget()
    {
        return _target;
    }


    public void setTarget(Class<?> clazz, Field field, Class<?> resourceType)
    {
        _targetClass = clazz;
        _target = field;
        _resourceClass = resourceType;
    }

    public void setTarget(Class<?> clazz, Method method, Class<?> arg, Class<?> resourceType)
    {
        _targetClass = clazz;
        _target = method;
        _resourceClass = resourceType;
        _paramClass = arg;
    }

    public void setTarget (Class<?> clazz, String target, Class<?> resourceType)
    {
        _targetClass = clazz;
        _resourceClass = resourceType;

        //first look for a javabeans style setter matching the targetName
        String setter = "set"+target.substring(0,1).toUpperCase(Locale.ENGLISH)+target.substring(1);
        try
        {
            LOG.debug("Looking for method for setter: "+setter+" with arg "+_resourceClass);
            _target = IntrospectionUtil.findMethod(clazz, setter, new Class[] {_resourceClass}, true, false);
            _targetClass = clazz;
            _paramClass = _resourceClass;
        }
        catch (NoSuchMethodException me)
        {
            //try as a field
            try
            {
                _target = IntrospectionUtil.findField(clazz, target, resourceType, true, false);
                _targetClass = clazz;
            }
            catch (NoSuchFieldException fe)
            {
                throw new IllegalArgumentException("No such field or method "+target+" on class "+_targetClass);
            }
        }

    }

    /**
     * Inject a value for a Resource from JNDI into an object
     * @param injectable the object to inject 
     */
    public void inject (Object injectable)
    {
        if (_target != null)
        {
            if (_target instanceof Field)
                injectField((Field)_target, injectable);
            else
                injectMethod((Method)_target, injectable);
        }
        else
            throw new IllegalStateException ("No method or field to inject with "+getJndiName());
    }


    /**
     * The Resource must already exist in the ENC of this webapp.
     * @return the injected valud
     * @throws NamingException if unable to lookup value
     */
    public Object lookupInjectedValue ()
    throws NamingException
    {
        InitialContext context = new InitialContext();
        return context.lookup("java:comp/env/"+getJndiName());
    }



    /**
     * Inject value from jndi into a field of an instance
     * @param field the field to inject into
     * @param injectable the value to inject
     */
    protected void injectField (Field field, Object injectable)
    {
        try
        {
            boolean accessibility = field.isAccessible();
            field.setAccessible(true);
            field.set(injectable, lookupInjectedValue());
            field.setAccessible(accessibility);
        }
        catch (Exception e)
        {
            LOG.warn(e);
            throw new IllegalStateException("Inject failed for field "+field.getName());
        }
    }

    /**
     * Inject value from jndi into a setter method of an instance
     * @param method the method to inject into
     * @param injectable the value to inject
     */
    protected void injectMethod (Method method, Object injectable)
    {
        try
        {
            boolean accessibility = method.isAccessible();
            method.setAccessible(true);
            method.invoke(injectable, new Object[] {lookupInjectedValue()});
            method.setAccessible(accessibility);
        }
        catch (Exception e)
        {
            LOG.warn(e);
            throw new IllegalStateException("Inject failed for method "+method.getName());
        }
    }

}
