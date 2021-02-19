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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * InjectionCollection
 * Map of classname to all injections requested on that class,
 * whether by declaration in web.xml or via equivalent annotations.
 * 
 * This class is not threadsafe for concurrent modifications, but is
 * threadsafe for readers with concurrent modifications.
 */
public class InjectionCollection
{
    private static final Logger LOG = Log.getLogger(InjectionCollection.class);

    public static final String INJECTION_COLLECTION = "org.eclipse.jetty.injectionCollection";

    private final ConcurrentMap<String, Set<Injection>> _injectionMap = new ConcurrentHashMap<>(); //map of classname to injections

    public void add(Injection injection)
    {
        if (injection == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring null Injection");
            return;
        }

        String name = injection.getTargetClass().getName();

        Set<Injection> injections = _injectionMap.get(name);
        if (injections == null)
        {
            injections = new CopyOnWriteArraySet<>();
            Set<Injection> tmp = _injectionMap.putIfAbsent(name, injections);
            if (tmp != null)
                injections = tmp;
        }

        boolean added = injections.add(injection);
        if (LOG.isDebugEnabled())
            LOG.debug("Adding injection for class={} on {} added={}", name, injection.getTarget().getName(), added);
    }

    public Set<Injection> getInjections(String className)
    {
        if (className == null)
            return null;

        return _injectionMap.get(className);
    }

    public Injection getInjection(String jndiName, Class<?> clazz, Field field)
    {
        if (field == null || clazz == null)
            return null;

        Set<Injection> injections = getInjections(clazz.getName());
        if (injections == null)
            return null;
        Iterator<Injection> itor = injections.iterator();
        Injection injection = null;
        while (itor.hasNext() && injection == null)
        {
            Injection i = itor.next();
            if (i.isField() && field.getName().equals(i.getTarget().getName()))
                injection = i;
        }

        return injection;
    }

    public Injection getInjection(String jndiName, Class<?> clazz, Method method, Class<?> paramClass)
    {
        if (clazz == null || method == null || paramClass == null)
            return null;

        Set<Injection> injections = getInjections(clazz.getName());
        if (injections == null)
            return null;
        Iterator<Injection> itor = injections.iterator();
        Injection injection = null;
        while (itor.hasNext() && injection == null)
        {
            Injection i = itor.next();
            if (i.isMethod() && i.getTarget().getName().equals(method.getName()) && paramClass.equals(i.getParamClass()))
                injection = i;
        }

        return injection;
    }

    public void inject(Object injectable)
    {
        if (injectable == null)
            return;

        //Get all injections pertinent to the Object by
        //looking at it's class hierarchy
        Class<?> clazz = injectable.getClass();

        while (clazz != null)
        {
            Set<Injection> injections = _injectionMap.get(clazz.getName());
            if (injections != null)
            {
                for (Injection i : injections)
                {
                    i.inject(injectable);
                }
            }

            clazz = clazz.getSuperclass();
        }
    }
}
