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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * LifeCycleCallbackCollection
 * 
 * This class collects the classes and methods that have been configured
 * in web.xml with postconstruct/predestroy callbacks, or that contain the
 * equivalent annotations.  It is also responsible for calling the 
 * callbacks.
 * 
 * This class is not threadsafe for concurrent modifications, but is
 * threadsafe for reading with concurrent modifications.
 */
public class LifeCycleCallbackCollection
{
    private static final Logger LOG = Log.getLogger(LifeCycleCallbackCollection.class);

    public static final String LIFECYCLE_CALLBACK_COLLECTION = "org.eclipse.jetty.lifecyleCallbackCollection";

    private final ConcurrentMap<String, List<LifeCycleCallback>> postConstructCallbacksMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<LifeCycleCallback>> preDestroyCallbacksMap = new ConcurrentHashMap<>();
    
    /**
     * Add a Callback to the list of callbacks.
     * 
     * @param callback the callback
     */
    public void add (LifeCycleCallback callback)
    {
        if ((callback==null) || (callback.getTargetClassName()==null))
        {
            if (LOG.isDebugEnabled()) LOG.debug("Ignoring empty LifeCycleCallback");
            return;
        }

        Map<String, List<LifeCycleCallback>> map = null;
        if (callback instanceof PreDestroyCallback)
            map = preDestroyCallbacksMap;
        if (callback instanceof PostConstructCallback)
            map = postConstructCallbacksMap;

        if (map == null)
            throw new IllegalArgumentException ("Unsupported lifecycle callback type: "+callback);
     
        List<LifeCycleCallback> callbacks = map.get(callback.getTargetClassName());
        if (callbacks==null)
        {
            callbacks = map.putIfAbsent(callback.getTargetClassName(), new CopyOnWriteArrayList<LifeCycleCallback>());
            if (callbacks == null)
                callbacks = map.get(callback.getTargetClassName());
        }

        //don't add another callback for exactly the same method
        if (!callbacks.contains(callback))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Adding callback for class={} on method={}", callback.getTargetClassName(), callback.getMethodName());
            callbacks.add(callback);
        }
    }

    public List<LifeCycleCallback> getPreDestroyCallbacks (Object o)
    {
        if (o == null)
            return null;
        
        Class<? extends Object> clazz = o.getClass();
        return preDestroyCallbacksMap.get(clazz.getName());
    }
    
    public List<LifeCycleCallback> getPostConstructCallbacks (Object o)
    {
        if (o == null)
            return null;
        
        Class<? extends Object> clazz = o.getClass();
        return postConstructCallbacksMap.get(clazz.getName());
    }
    
    /**
     * Call the method, if one exists, that is annotated with <code>&#064;PostConstruct</code>
     * or with <code>&lt;post-construct&gt;</code> in web.xml
     * @param o the object on which to attempt the callback
     * @throws Exception if unable to call {@link PostConstructCallback}
     */
    public void callPostConstructCallback (Object o)
    throws Exception
    {
        if (o == null)
            return;
        
        Class<? extends Object> clazz = o.getClass();
        List<LifeCycleCallback> callbacks = postConstructCallbacksMap.get(clazz.getName());

        if (callbacks == null)
            return;

        for (int i=0;i<callbacks.size();i++)
        {
            ((LifeCycleCallback)callbacks.get(i)).callback(o);
        }
    }

    
    /**
     * Call the method, if one exists, that is annotated with <code>&#064;PreDestroy</code>
     * or with <code>&lt;pre-destroy&gt;</code> in web.xml
     * @param o the object on which to attempt the callback
     * @throws Exception if unable to call {@link PreDestroyCallback}
     */
    public void callPreDestroyCallback (Object o)
    throws Exception
    {
        if (o == null)
            return;
        
        Class<? extends Object> clazz = o.getClass();
        List<LifeCycleCallback> callbacks = preDestroyCallbacksMap.get(clazz.getName());
        if (callbacks == null)
            return;
        
        for (int i=0;i<callbacks.size();i++)
            ((LifeCycleCallback)callbacks.get(i)).callback(o);
    }
    
    /**
     * Generate a read-only view of the post-construct callbacks
     * @return the map of {@link PostConstructCallback}s
     */
    public Map<String, List<LifeCycleCallback>> getPostConstructCallbackMap()
    {
        return Collections.unmodifiableMap(postConstructCallbacksMap);
    }
    
    /**
     * Generate a read-only view of the pre-destroy callbacks
     * @return the map of {@link PreDestroyCallback}s
     */
    public Map<String, List<LifeCycleCallback>> getPreDestroyCallbackMap()
    {
        return Collections.unmodifiableMap(preDestroyCallbacksMap);
    }
    
    /**
     * Amalgamate all post-construct callbacks and return a read only list
     * @return the collection of {@link PostConstructCallback}s
     */
    public Collection<LifeCycleCallback> getPostConstructCallbacks()
    {
        List<LifeCycleCallback> list = new ArrayList<LifeCycleCallback>();
        for (String s:postConstructCallbacksMap.keySet())
        {
            list.addAll(postConstructCallbacksMap.get(s));
        }
        return Collections.unmodifiableCollection(list);
    }
    
    /**
     * Amalgamate all pre-destroy callbacks and return a read only list
     * @return the collection of {@link PreDestroyCallback}s
     */
    public Collection<LifeCycleCallback> getPreDestroyCallbacks()
    {
        List<LifeCycleCallback> list = new ArrayList<LifeCycleCallback>();
        for (String s:preDestroyCallbacksMap.keySet())
        {
            list.addAll(preDestroyCallbacksMap.get(s));
        }
        return Collections.unmodifiableCollection(list);
    }
    
}
