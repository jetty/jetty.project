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

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.plus.annotation.AbstractAccessControl;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.plus.annotation.DenyAll;
import org.eclipse.jetty.plus.annotation.PermitAll;
import org.eclipse.jetty.plus.annotation.RolesAllowed;
import org.eclipse.jetty.plus.annotation.TransportProtected;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlProcessor;
import org.eclipse.jetty.webapp.WebXmlProcessor.Descriptor;

/**
 * Configuration
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
       Integer i = (Integer)context.getAttribute(WEBXML_VERSION);
       int webxmlVersion = (i == null? 0 : i.intValue());
      
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
            parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new WebServletAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.servlet.annotation.WebFilter", new WebFilterAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.servlet.annotation.WebListener", new WebListenerAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.Resource", new ResourceAnnotationHandler (context));
            parser.registerAnnotationHandler("javax.annotation.Resources", new ResourcesAnnotationHandler (context));
            parser.registerAnnotationHandler("javax.annotation.PostConstruct", new PostConstructAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.PreDestroy", new PreDestroyAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.RunAs", new RunAsAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.DenyAll", new DenyAllAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.PermitAll", new PermitAllAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.RolesAllowed", new RolesAllowedAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.TransportProtected", new TransportProtectedAnnotationHandler(context));
            ClassInheritanceHandler classHandler = new ClassInheritanceHandler();
            parser.registerClassHandler(classHandler);
            registerServletContainerInitializerAnnotationHandlers(context, parser);
            
            if (webxmlVersion >= 30 || context.isConfigurationDiscovered())
            {
                System.err.println("SCANNING ALL ANNOTATIONS: webxmlVersion="+webxmlVersion+" configurationDiscovered="+context.isConfigurationDiscovered());
                parseContainerPath(context, parser);
                parseWebInfLib (context, parser);
                parseWebInfClasses(context, parser);
            } 
            else
            {
                System.err.println("SCANNING ONLY WEB.XML ANNOTATIONS");
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
        if (!(context.getSecurityHandler() instanceof ConstraintAware))
        {
            Log.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }
        ConstraintAware securityHandler = (ConstraintAware)context.getSecurityHandler();
        ConstraintMapping[] constraintMappings = securityHandler.getConstraintMappings();
        ServletMapping[] mappings = context.getServletHandler().getServletMappings();
        
        //process Security Annotations class by class
        Map<String, List<AbstractAccessControl>> securityAnnotations = (Map<String, List<AbstractAccessControl>>) context.getAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS);
        for (Map.Entry<String, List<AbstractAccessControl>> e: securityAnnotations.entrySet())
        {
            //Find all url-patterns that have been mapped to this class and convert to <security-constraints>
          
            for (ServletMapping mapping : mappings)
            {
              //Check the name of the servlet that this mapping applies to, and then find the ServletHolder for it to find it's class
                ServletHolder holder = context.getServletHandler().getServlet(mapping.getServletName());
                if (!holder.getClassName().equals(e.getKey()))
                    continue;
                
                //If the class is the same as one on the securityAnnotation then get its url mappings
                String[] pathSpecs = mapping.getPathSpecs();
                
                //Now that we have the set of url mappings, see if there are any security constraints that would
                //already apply, in which case we ignore this annotation
                if (constraintMappings != null)
                {
                    for (ConstraintMapping constraintMapping : constraintMappings)
                    {
                       //TODO
                    }
                }
              
               
                
                
                //Otherwise, we go about constructing a security-constraint that satisfies all of the annotations for this class
            }
        }
    }
    

    public void registerServletContainerInitializerAnnotationHandlers (WebAppContext context, AnnotationParser parser)
    {     
        //Get all ServletContainerInitializers, and check them for HandlesTypes annotations.
        //For each class in the HandlesTypes value, if it IS an annotation, register a handler
        //that will record the classes that have that annotation.
        //If it is NOT an annotation, then we will interrogate the type hierarchy discovered during
        //parsing later on to find the applicable classes.
        ArrayList<ContainerInitializer> initializers = new ArrayList<ContainerInitializer>();
        context.setAttribute(ContainerInitializerConfiguration.CONTAINER_INITIALIZERS, initializers);
        
        //We use the ServiceLoader mechanism to find the ServletContainerInitializer classes to inspect
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
                        }
                    }
                    else
                        System.err.println("No classes in HandlesTypes on initializer "+i.getClass());
                }
                else
                    System.err.println("No annotation on initializer "+i.getClass());
            }
        }
    }
}
