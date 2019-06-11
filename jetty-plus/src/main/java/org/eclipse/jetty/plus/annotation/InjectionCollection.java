//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * InjectionCollection
 *
 *
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

    private final ConcurrentMap<String, List<Injection>> _injectionMap = new ConcurrentHashMap<>();//map of classname to injections

    public void add(Injection injection)
    {
        if ((injection == null) || injection.getTargetClass() == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring null Injection");
            return;
        }

        String canonicalName = injection.getTargetClass().getCanonicalName();

        List<Injection> injections = _injectionMap.get(canonicalName);
        if (injections == null)
        {
            injections = _injectionMap.putIfAbsent(canonicalName, new CopyOnWriteArrayList<>());
            if (injections == null)
                injections = _injectionMap.get(canonicalName);
        }

        if (!injections.contains(injection))
        {
            injections.add(injection);
            if (LOG.isDebugEnabled())
                LOG.debug("Adding injection for class={} on {}", canonicalName, injection.getTarget().getName());
        }
    }

    public List<Injection> getInjections(String className)
    {
        if (className == null)
            return null;

        return _injectionMap.get(className);
    }

    public Injection getInjection(String jndiName, Class<?> clazz, Field field)
    {
        if (field == null || clazz == null)
            return null;

        List<Injection> injections = getInjections(clazz.getCanonicalName());
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

        List<Injection> injections = getInjections(clazz.getCanonicalName());
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
            List<Injection> injections = _injectionMap.get(clazz.getCanonicalName());
            if (injections != null)
            {
                for (Injection i : injections)
                    i.inject(injectable);
            }

            clazz = clazz.getSuperclass();
        }
    }
}
