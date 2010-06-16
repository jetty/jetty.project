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

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Configuration for Annotations
 *
 *
 */
public class AnnotationConfiguration extends AbstractConfiguration
{
    public static final String CLASS_INHERITANCE_MAP  = "org.eclipse.jetty.classInheritanceMap";
    
    
    public void preConfigure(final WebAppContext context) throws Exception
    {
    }
   
    
    public void configure(WebAppContext context) throws Exception
    {
       Boolean b = (Boolean)context.getAttribute(METADATA_COMPLETE);
       boolean metadataComplete = (b != null && b.booleanValue());
      
       WebAppDecoratorWrapper wrapper = new WebAppDecoratorWrapper(context, context.getDecorator());
       context.setDecorator(wrapper);
      
        if (metadataComplete)
        {
            //Never scan any jars or classes for annotations if metadata is complete
            if (Log.isDebugEnabled()) Log.debug("Metadata-complete==true,  not processing annotations for context "+context);
            return;
        }
        else 
        {
            //Only scan jars and classes if metadata is not complete and the web app is version 3.0, or
            //a 2.5 version webapp that has specifically asked to discover annotations
            if (Log.isDebugEnabled()) Log.debug("parsing annotations");
                       
            AnnotationParser parser = new AnnotationParser();
            //Discoverable annotations - those that you have to look for without loading a class
            parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new WebServletAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.servlet.annotation.WebFilter", new WebFilterAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.servlet.annotation.WebListener", new WebListenerAnnotationHandler(context));
            
            ClassInheritanceHandler classHandler = new ClassInheritanceHandler();
            parser.registerClassHandler(classHandler);
            registerServletContainerInitializerAnnotationHandlers(context, parser);
            
            if (context.getServletContext().getEffectiveMajorVersion() >= 3 || context.isConfigurationDiscovered())
            {
                if (Log.isDebugEnabled()) Log.debug("Scanning all classses for annotations: webxmlVersion="+context.getServletContext().getEffectiveMajorVersion()+" configurationDiscovered="+context.isConfigurationDiscovered());
                parseContainerPath(context, parser);
                //email from Rajiv Mordani jsrs 315 7 April 2010
                //    If there is a <others/> then the ordering should be 
                //          WEB-INF/classes the order of the declared elements + others.
                //    In case there is no others then it is 
                //          WEB-INF/classes + order of the elements.
                parseWebInfClasses(context, parser);
                parseWebInfLib (context, parser);
            } 
            else
            {
                if (Log.isDebugEnabled()) Log.debug("Scanning only classes in web.xml for annotations");
                parse25Classes(context, parser);
            }
            
            //save the type inheritance map created by the parser for later reference
            context.setAttribute(CLASS_INHERITANCE_MAP, classHandler.getMap());
        }    
    }



    public void deconfigure(WebAppContext context) throws Exception
    {
        
    }




    public void postConfigure(WebAppContext context) throws Exception
    {
        //context.setAttribute(CLASS_INHERITANCE_MAP, null);
    }
    

    public void registerServletContainerInitializerAnnotationHandlers (WebAppContext context, AnnotationParser parser)
    throws Exception
    {     
        //TODO verify my interpretation of the spec. That is, that metadata-complete has nothing
        //to do with finding the ServletContainerInitializers, classes designated to be of interest to them,
        //or even calling them on startup. 
        
        //Get all ServletContainerInitializers, and check them for HandlesTypes annotations.
        //For each class in the HandlesTypes value, if it IS an annotation, register a handler
        //that will record the classes that have that annotation.
        //If it is NOT an annotation, then we will interrogate the type hierarchy discovered during
        //parsing later on to find the applicable classes.
        ArrayList<ContainerInitializer> initializers = new ArrayList<ContainerInitializer>();
        context.setAttribute(ContainerInitializerConfiguration.CONTAINER_INITIALIZERS, initializers);
        
        //We use the ServiceLoader mechanism to find the ServletContainerInitializer classes to inspect
        ServiceLoader<ServletContainerInitializer> loadedInitializers = ServiceLoader.load(ServletContainerInitializer.class, context.getClassLoader());
        List<String> orderedJars = (List<String>) context.getAttribute(ServletContext.ORDERED_LIBS);
        if (loadedInitializers != null)
        {
            for (ServletContainerInitializer service : loadedInitializers)
            {
                if (!isFromExcludedJar(context, service))
                { 
                    HandlesTypes annotation = service.getClass().getAnnotation(HandlesTypes.class);
                    ContainerInitializer initializer = new ContainerInitializer();
                    initializer.setTarget(service);
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
                                    if (Log.isDebugEnabled()) Log.debug("Registering annotation handler for "+c.getName());
                                    parser.registerAnnotationHandler(c.getName(), new ContainerInitializerAnnotationHandler(initializer, c));
                                }
                            }
                        }
                        else
                            Log.info("No classes in HandlesTypes on initializer "+service.getClass());
                    }
                    else
                        Log.info("No annotation on initializer "+service.getClass());
                }
            }
        }
    }
    
    /**
     * Check to see if the ServletContainerIntializer loaded via the ServiceLoader came
     * from a jar that is excluded by the fragment ordering. See ServletSpec 3.0 p.85.
     * @param orderedJars
     * @param service
     * @return
     */
    public boolean isFromExcludedJar (WebAppContext context, ServletContainerInitializer service)
    throws Exception
    {
        List<String> orderedLibs = (List<String>)context.getAttribute(ServletContext.ORDERED_LIBS);

        //If no ordering, nothing is excluded
        if (orderedLibs == null)
            return false;

        //ordering that does not include any jars, everything excluded
        if (orderedLibs.isEmpty())
            return true; 


        String loadingJarName = Thread.currentThread().getContextClassLoader().getResource(service.getClass().getName().replace('.','/')+".class").toString();

        int i = loadingJarName.indexOf(".jar");  
        if (i < 0)
            return false; //not from a jar therefore not from WEB-INF so not excludable

        int j = loadingJarName.lastIndexOf("/", i);
        loadingJarName = loadingJarName.substring(j+1,i+4);

        return (!orderedLibs.contains(loadingJarName));
    }
}
