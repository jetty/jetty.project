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

package org.eclipse.jetty.ee9.plus.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.eclipse.jetty.util.IntrospectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Injection
 * <p>
 * Represents the injection of a resource into a target (method or field).
 * The injection is performed by doing an ENC lookup using the jndi
 * name provided, and setting the object obtained on the target.
 */
public class Injection
{
    private static final Logger LOG = LoggerFactory.getLogger(Injection.class);

    private final Class<?> _targetClass;
    private final String _jndiName;
    private final String _mappingName;
    private final Member _target;
    private final Class<?> _paramClass;
    private final Class<?> _resourceClass;

    public Injection(Class<?> clazz, Field field, Class<?> resourceType, String jndiName, String mappingName)
    {
        _targetClass = Objects.requireNonNull(clazz);
        _target = Objects.requireNonNull(field);
        _resourceClass = resourceType;
        _paramClass = null;
        _jndiName = jndiName;
        _mappingName = mappingName;
    }

    public Injection(Class<?> clazz, Method method, Class<?> arg, Class<?> resourceType, String jndiName, String mappingName)
    {
        _targetClass = Objects.requireNonNull(clazz);
        _target = Objects.requireNonNull(method);
        _resourceClass = resourceType;
        _paramClass = arg;
        _jndiName = jndiName;
        _mappingName = mappingName;
    }

    public Injection(Class<?> clazz, String target, Class<?> resourceType, String jndiName, String mappingName)
    {
        _targetClass = Objects.requireNonNull(clazz);
        Objects.requireNonNull(target);
        _resourceClass = resourceType;
        _jndiName = jndiName;
        _mappingName = mappingName;

        Member tmpTarget = null;
        Class<?> tmpParamClass = null;

        //first look for a javabeans style setter matching the targetName
        String setter = "set" + target.substring(0, 1).toUpperCase(Locale.ENGLISH) + target.substring(1);
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Looking for method for setter: {} with arg {}", setter, _resourceClass);
            tmpTarget = IntrospectionUtil.findMethod(clazz, setter, new Class[]{_resourceClass}, true, false);
            tmpParamClass = _resourceClass;
        }
        catch (NoSuchMethodException nsme)
        {
            //try as a field
            try
            {
                tmpTarget = IntrospectionUtil.findField(clazz, target, resourceType, true, false);
                tmpParamClass = null;
            }
            catch (NoSuchFieldException nsfe)
            {
                nsme.addSuppressed(nsfe);
                throw new IllegalArgumentException("No such field or method " + target + " on class " + _targetClass, nsme);
            }
        }

        _target = tmpTarget;
        _paramClass = tmpParamClass;
    }

    /**
     * @return the _className
     */
    public Class<?> getTargetClass()
    {
        return _targetClass;
    }

    public Class<?> getParamClass()
    {
        return _paramClass;
    }

    public Class<?> getResourceClass()
    {
        return _resourceClass;
    }

    public boolean isField()
    {
        return (Field.class.isInstance(_target));
    }

    public boolean isMethod()
    {
        return (Method.class.isInstance(_target));
    }

    /**
     * @return the jndiName
     */
    public String getJndiName()
    {
        return _jndiName;
    }

    /**
    
     * @return the mappingName
     */
    public String getMappingName()
    {
        return _mappingName;
    }

    /**
     * @return the target
     */
    public Member getTarget()
    {
        return _target;
    }

    /**
     * Inject a value for a Resource from JNDI into an object
     *
     * @param injectable the object to inject
     */
    public void inject(Object injectable)
    {
        if (isField())
            injectField((Field)_target, injectable);
        else if (isMethod())
            injectMethod((Method)_target, injectable);
        else
            throw new IllegalStateException("Neither field nor method injection");
    }

    /**
     * The Resource must already exist in the ENC of this webapp.
     *
     * @return the injected valud
     * @throws NamingException if unable to lookup value
     */
    public Object lookupInjectedValue()
        throws NamingException
    {
        InitialContext context = new InitialContext();
        return context.lookup("java:comp/env/" + getJndiName());
    }

    /**
     * Inject value from jndi into a field of an instance
     *
     * @param field the field to inject into
     * @param injectable the value to inject
     */
    protected void injectField(Field field, Object injectable)
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
            LOG.warn("Unable to inject field {} with {}", field, injectable, e);
            throw new IllegalStateException("Inject failed for field " + field.getName(), e);
        }
    }

    /**
     * Inject value from jndi into a setter method of an instance
     *
     * @param method the method to inject into
     * @param injectable the value to inject
     */
    protected void injectMethod(Method method, Object injectable)
    {
        try
        {
            boolean accessibility = method.isAccessible();
            method.setAccessible(true);
            method.invoke(injectable, new Object[]{lookupInjectedValue()});
            method.setAccessible(accessibility);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to inject method {} with {}", method, injectable, e);
            throw new IllegalStateException("Inject failed for method " + method.getName());
        }
    }
}
