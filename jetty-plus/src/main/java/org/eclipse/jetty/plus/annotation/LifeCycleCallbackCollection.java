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
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;


/**
 * LifeCycleCallbackCollection
 *
 *
 */
public class LifeCycleCallbackCollection
{
    public static final String LIFECYCLE_CALLBACK_COLLECTION = "org.eclipse.jetty.lifecyleCallbackCollection";
    private HashMap postConstructCallbacksMap = new HashMap();
    private HashMap preDestroyCallbacksMap = new HashMap();
    
    
 
    
    
    /**
     * Add a Callback to the list of callbacks.
     * 
     * @param callback
     */
    public void add (LifeCycleCallback callback)
    {
        if ((callback==null) || (callback.getTargetClass()==null) || (callback.getTarget()==null))
            return;

        if (Log.isDebugEnabled())
            Log.debug("Adding callback for class="+callback.getTargetClass()+ " on "+callback.getTarget());
        Map map = null;
        if (callback instanceof PreDestroyCallback)
            map = preDestroyCallbacksMap;
        if (callback instanceof PostConstructCallback)
            map = postConstructCallbacksMap;

        if (map == null)
            throw new IllegalArgumentException ("Unsupported lifecycle callback type: "+callback);

     
        List callbacks = (List)map.get(callback.getTargetClass());
        if (callbacks==null)
        {
            callbacks = new ArrayList();
            map.put(callback.getTargetClass(), callbacks);
        }
       
        //don't add another callback for exactly the same method
        if (!callbacks.contains(callback))
            callbacks.add(callback);
    }

    public List getPreDestroyCallbacks (Object o)
    {
        if (o == null)
            return null;
        
        Class clazz = o.getClass();
        return (List)preDestroyCallbacksMap.get(clazz);
    }
    
    public List getPostConstructCallbacks (Object o)
    {
        if (o == null)
            return null;
        
        Class clazz = o.getClass();
        return (List)postConstructCallbacksMap.get(clazz);
    }
    
    /**
     * Call the method, if one exists, that is annotated with PostConstruct
     * or with &lt;post-construct&gt; in web.xml
     * @param o the object on which to attempt the callback
     * @throws Exception
     */
    public void callPostConstructCallback (Object o)
    throws Exception
    {
        if (o == null)
            return;
        
        Class clazz = o.getClass();
        List callbacks = (List)postConstructCallbacksMap.get(clazz);
        
        if (callbacks == null)
            return;
        
        for (int i=0;i<callbacks.size();i++)
            ((LifeCycleCallback)callbacks.get(i)).callback(o);
    }
    
    
    /**
     * Call the method, if one exists, that is annotated with PreDestroy
     * or with &lt;pre-destroy&gt; in web.xml
     * @param o the object on which to attempt the callback
     */
    public void callPreDestroyCallback (Object o)
    throws Exception
    {
        if (o == null)
            return;
        
        Class clazz = o.getClass();
        List callbacks = (List)preDestroyCallbacksMap.get(clazz);
        if (callbacks == null)
            return;
        
        for (int i=0;i<callbacks.size();i++)
            ((LifeCycleCallback)callbacks.get(i)).callback(o);
    }
}
