//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;


/**
 * ServletContainerInitializersStarter
 *
 * Call the onStartup() method on all ServletContainerInitializers, after having 
 * found all applicable classes (if any) to pass in as args.
 */
public class ServletContainerInitializersStarter extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
{
    private static final Logger LOG = Log.getLogger(ServletContainerInitializersStarter.class);
    WebAppContext _context;
    
    /**
     * @param context
     */
    public ServletContainerInitializersStarter(WebAppContext context)
    {
        _context = context;
    }
 
   /** 
    * Call the doStart method of the ServletContainerInitializers
    * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
    */
    public void doStart()
    {
        List<ContainerInitializer> initializers = (List<ContainerInitializer>)_context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZERS);
        if (initializers == null)
            return;

       ConcurrentHashMap<String, ConcurrentHashSet<String>> map = ( ConcurrentHashMap<String, ConcurrentHashSet<String>>)_context.getAttribute(AnnotationConfiguration.CLASS_INHERITANCE_MAP);
        
        for (ContainerInitializer i : initializers)
        {
            configureHandlesTypes(_context, i, map);

            //instantiate ServletContainerInitializers, call doStart
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Calling ServletContainerInitializer "+i.getTarget().getClass().getName());
                i.callStartup(_context);
            }
            catch (Exception e)
            {
                LOG.warn(e);
                throw new RuntimeException(e);
            }
        }
    }
  

    private void configureHandlesTypes (WebAppContext context, ContainerInitializer initializer, ConcurrentHashMap<String, ConcurrentHashSet<String>> classMap)
    {
        doHandlesTypesAnnotations(context, initializer, classMap);
        doHandlesTypesClasses(context, initializer, classMap);
    }
    
    private void doHandlesTypesAnnotations(WebAppContext context, ContainerInitializer initializer, ConcurrentHashMap<String, ConcurrentHashSet<String>> classMap)
    {
        if (initializer == null)
            return;
        if (context == null)
            throw new IllegalArgumentException("WebAppContext null");
        
        //We have already found the classes that directly have an annotation that was in the HandlesTypes
        //annotation of the ServletContainerInitializer. For each of those classes, walk the inheritance
        //hierarchy to find classes that extend or implement them.
        Set<String> annotatedClassNames = initializer.getAnnotatedTypeNames();
        if (annotatedClassNames != null && !annotatedClassNames.isEmpty())
        {
            if (classMap == null)
                throw new IllegalStateException ("No class hierarchy");

            for (String name : annotatedClassNames)
            {
                //add the class that has the annotation
                initializer.addApplicableTypeName(name);

                //find and add the classes that inherit the annotation               
                addInheritedTypes(classMap, initializer, (ConcurrentHashSet<String>)classMap.get(name));
            }
        }
    }

    

    private void doHandlesTypesClasses (WebAppContext context, ContainerInitializer initializer, ConcurrentHashMap<String, ConcurrentHashSet<String>> classMap)
    {
        if (initializer == null)
            return;
        if (context == null)
            throw new IllegalArgumentException("WebAppContext null");

        //Now we need to look at the HandlesTypes classes that were not annotations. We need to
        //find all classes that extend or implement them.
        if (initializer.getInterestedTypes() != null)
        {
            if (classMap == null)
                throw new IllegalStateException ("No class hierarchy");

            for (Class c : initializer.getInterestedTypes())
            {
                if (!c.isAnnotation())
                {
                    //find and add the classes that implement or extend the class.
                    //but not including the class itself
                    addInheritedTypes(classMap, initializer, (ConcurrentHashSet<String>)classMap.get(c.getName()));
                }
            }
        }
    }
    
    
    private void addInheritedTypes (ConcurrentHashMap<String, ConcurrentHashSet<String>> classMap, ContainerInitializer initializer, ConcurrentHashSet<String> names)
    {
        if (names == null || names.isEmpty())
            return;
     
        for (String s : names)
        {
            //add the name of the class
            initializer.addApplicableTypeName(s);

            //walk the hierarchy and find all types that extend or implement the class
            addInheritedTypes(classMap, initializer, (ConcurrentHashSet<String>)classMap.get(s));
        }
    }
}
