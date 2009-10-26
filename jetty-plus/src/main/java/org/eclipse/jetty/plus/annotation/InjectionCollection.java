// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.plus.annotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.log.Log;

/**
 * InjectionCollection
 *
 *
 */
public class InjectionCollection
{
    public static final String INJECTION_COLLECTION = "org.eclipse.jetty.injectionCollection";
    private HashMap<String, List<Injection>> _injectionMap = new HashMap<String, List<Injection>>();//map of classname to injections
    
    public void add (Injection injection)
    {
        if ((injection==null) || injection.getTargetClassName()==null) 
            return;
        
        if (Log.isDebugEnabled())
            Log.debug("Adding injection for class="+(injection.getTargetClassName()+ " on a "+(injection.isField()?injection.getFieldName():injection.getMethodName())));
   
        
        List<Injection> injections = (List<Injection>)_injectionMap.get(injection.getTargetClassName());
        if (injections==null)
        {
            injections = new ArrayList<Injection>();
            _injectionMap.put(injection.getTargetClassName(), injections);
        }
        
        injections.add(injection);
    }

 
    public List<Injection>  getInjections (String className)
    {
        if (className==null)
            return null;

        return _injectionMap.get(className);
    }
    
    
    public Injection getInjection (String jndiName, String className, String fieldName)
    {
        if (fieldName == null || className == null)
            return null;
        
        List<Injection> injections = getInjections(className);
        if (injections == null)
            return null;
        Iterator<Injection> itor = injections.iterator();
        Injection injection = null;
        while (itor.hasNext() && injection == null)
        {
            Injection i = itor.next();
            if (fieldName.equals(i.getFieldName()))
                injection = i;
        }
        
        return injection;
    }
    
    public Injection getInjection (String jndiName, String className, String methodName, String paramCanonicalName)
    {
        if (className == null || methodName == null || paramCanonicalName == null)
            return null;
        
        List<Injection> injections = getInjections(className);
        if (injections == null)
            return null;
        Iterator<Injection> itor = injections.iterator();
        Injection injection = null;
        while (itor.hasNext() && injection == null)
        {
            Injection i = itor.next();
            if (methodName.equals(i.getMethodName()) && paramCanonicalName.equals(i.getParamCanonicalName()))
                injection = i;
        }
        
        return injection;
    }
    
    
    public void inject (Object injectable)
    throws Exception
    {
        if (injectable==null)
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
