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


package org.eclipse.jetty.annotations;


import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.webapp.WebAppContext;

public class ContainerInitializerConfiguration extends AbstractConfiguration
{
    public static final String CONTAINER_INITIALIZERS = "org.eclipse.jetty.containerInitializers";

    public void preConfigure(WebAppContext context) throws Exception
    {  
    }

    public void configure(WebAppContext context) throws Exception
    {
        System.err.println("In ContainerInitializerConfiguration.preConfigure");
        ArrayList<ContainerInitializer> initializers = new ArrayList<ContainerInitializer>();
        context.setAttribute(CONTAINER_INITIALIZERS, initializers);
        AnnotationParser parser = new AnnotationParser();
     
        //Get all ServletContainerInitializers, and check them for HandlesTypes annotations.
        //For each class in the HandlesTypes value, if it is an annotation, then scan for
        //classes that have that annotation. If it is NOT an annotation, then scan for
        //classes that implement or extend it.
        ServiceLoader<ServletContainerInitializer> loadedInitializers = ServiceLoader.load(ServletContainerInitializer.class);
        if (loadedInitializers != null)
        {
            for (ServletContainerInitializer i : loadedInitializers)
            {
                HandlesTypes annotation = i.getClass().getAnnotation(HandlesTypes.class);
                ContainerInitializer initializer = new ContainerInitializer();
                initializer.setTarget(i);
                initializers.add(initializer);
                if (annotation != null)
                {
                    Class[] classes = annotation.value();
                    if (classes != null)
                    {
                        initializer.setInterestedTypes(classes);
                        for (Class c: classes)
                        {
                            if (c.isAnnotation())
                            {
                                System.err.println("Registering annotation handler for "+c.getName());
                                parser.registerAnnotationHandler(c.getName(), new ContainerInitializerAnnotationHandler(initializer, c));
                            }
                            else
                            {
                                System.err.println("Registering class handler for class "+c.getName());
                                parser.registerClassHandler(new ContainerInitializerClassHandler(initializer, c));
                            }
                        }
                    }
                    else
                        System.err.println("No classes in HandlesTypes on initializer "+i.getClass());
                }
                else
                    System.err.println("No annotation on initializer "+i.getClass());
            }
      
            Integer i = (Integer)context.getAttribute(WEBXML_VERSION);
            int webxmlVersion = (i == null? 0 : i.intValue());

            if (webxmlVersion >= 30 || context.isConfigurationDiscovered())
            {
                parseContainerPath(context, parser);
                parseWebInfLib (context, parser);
                parseWebInfClasses(context, parser);
            } 
            else
            {
                parse25Classes(context, parser);
            }
        }
        else
        {
            System.err.println("No ServletContainerInitializers loaded");
        }
    }
    
    public void postConfigure(WebAppContext context) throws Exception
    {
        //instantiate ServletContainerInitializers, call doStart
        List<ContainerInitializer> initializers = (List<ContainerInitializer>)context.getAttribute(CONTAINER_INITIALIZERS);
        for (ContainerInitializer i : initializers)
            i.callStartup(context.getServletContext());
    }

    public void deconfigure(WebAppContext context) throws Exception
    {  
    }

   
}
