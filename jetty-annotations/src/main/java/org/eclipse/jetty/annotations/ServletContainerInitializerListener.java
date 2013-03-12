//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * ServletContainerInitializerListener
 *
 *
 */
public class ServletContainerInitializerListener extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(ServletContainerInitializerListener.class);
    protected WebAppContext _context = null;
    
    
    public void setWebAppContext (WebAppContext context)
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
        MultiMap classMap = (MultiMap)_context.getAttribute(AnnotationConfiguration.CLASS_INHERITANCE_MAP);
        
        if (initializers != null)
        {
            for (ContainerInitializer i : initializers)
            {
                //We have already found the classes that directly have an annotation that was in the HandlesTypes
                //annotation of the ServletContainerInitializer. For each of those classes, walk the inheritance
                //hierarchy to find classes that extend or implement them.
                if (i.getAnnotatedTypeNames() != null)
                {
                    Set<String> annotatedClassNames = new HashSet<String>(i.getAnnotatedTypeNames());
                    for (String name : annotatedClassNames)
                    {
                        //add the class with the annotation
                        i.addApplicableTypeName(name);
                        //add the classes that inherit the annotation
                        if (classMap != null)
                        {
                            List<String> implementsOrExtends = (List<String>)classMap.getValues(name);
                            if (implementsOrExtends != null && !implementsOrExtends.isEmpty())
                                addInheritedTypes(classMap, i, implementsOrExtends);
                        }
                    }
                }


                //Now we need to look at the HandlesTypes classes that were not annotations. We need to
                //find all classes that extend or implement them.
                if (i.getInterestedTypes() != null)
                {
                    for (Class c : i.getInterestedTypes())
                    {
                        if (!c.isAnnotation())
                        {
                            //add the classes that implement or extend the class.
                            //TODO but not including the class itself?
                            if (classMap != null)
                            {
                                List<String> implementsOrExtends = (List<String>)classMap.getValues(c.getName());
                                if (implementsOrExtends != null && !implementsOrExtends.isEmpty())
                                    addInheritedTypes(classMap, i, implementsOrExtends);
                            }
                        }
                    }
                }

                //instantiate ServletContainerInitializers, call doStart
                try
                {
                    i.callStartup(_context);
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                    throw new RuntimeException(e);
                }
            }
        }       
    }

    
    void addInheritedTypes (MultiMap classMap, ContainerInitializer initializer, List<String> applicableTypes)
    {
        for (String s : applicableTypes)
        {
            //add the name of the class that extends or implements
            initializer.addApplicableTypeName(s);
            
            //walk the hierarchy and find all types that extend or implement it
            List<String> implementsOrExtends = (List<String>)classMap.getValues(s);
            if (implementsOrExtends != null && !implementsOrExtends.isEmpty())
                addInheritedTypes (classMap, initializer, implementsOrExtends);
        }
    }
    
    
   
    /** 
     * Nothing to do for ServletContainerInitializers on stop
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    public void doStop()
    {
       
    }

}
