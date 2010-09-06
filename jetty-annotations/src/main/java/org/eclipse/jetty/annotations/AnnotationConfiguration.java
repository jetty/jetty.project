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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.annotation.HandlesTypes;


import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.eclipse.jetty.webapp.WebDescriptor.MetaDataComplete;

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
   
    @Override
    public void configure(WebAppContext context) throws Exception
    {
       boolean metadataComplete = context.getMetaData().isMetaDataComplete();
       context.addDecorator(new AnnotationDecorator(context));   
      
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
            
            //save the type inheritance map created by the parser for later reference
            context.setAttribute(CLASS_INHERITANCE_MAP, classHandler.getMap());
        }    
    }

    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
        context.addDecorator(new AnnotationDecorator(context));   
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
                            if (Log.isDebugEnabled()) Log.debug("No classes in HandlesTypes on initializer "+service.getClass());
                    }
                    else
                        if (Log.isDebugEnabled()) Log.debug("No annotation on initializer "+service.getClass());
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
        List<Resource> orderedJars = context.getMetaData().getOrderedWebInfJars();

        //If no ordering, nothing is excluded
        if (context.getMetaData().getOrdering() == null)
            return false;

        //there is an ordering, but there are no jars resulting from the ordering, everything excluded
        if (orderedJars.isEmpty())
            return true; 

        String loadingJarName = Thread.currentThread().getContextClassLoader().getResource(service.getClass().getName().replace('.','/')+".class").toString();

        int i = loadingJarName.indexOf(".jar");  
        if (i < 0)
            return false; //not from a jar therefore not from WEB-INF so not excludable

        loadingJarName = loadingJarName.substring(0,i+4);
        loadingJarName = (loadingJarName.startsWith("jar:")?loadingJarName.substring(4):loadingJarName);
        URI loadingJarURI = Resource.newResource(loadingJarName).getURI();
        boolean found = false;
        Iterator<Resource> itor = orderedJars.iterator();
        while (!found && itor.hasNext())
        {
            Resource r = itor.next();         
            found = r.getURI().equals(loadingJarURI);
        }

        return !found;
    }
   
    public void parseContainerPath (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        Log.debug("Scanning container jars");
        
        //clear any previously discovered annotations
        clearAnnotationList(parser.getAnnotationHandlers());       
        
        //Convert from Resource to URI
        ArrayList<URI> containerUris = new ArrayList<URI>();
        for (Resource r : context.getMetaData().getOrderedContainerJars())
        {
            URI uri = r.getURI();
                containerUris.add(uri);          
        }
        
        parser.parse (containerUris.toArray(new URI[containerUris.size()]),
                new ClassNameResolver ()
                {
                    public boolean isExcluded (String name)
                    {
                        if (context.isSystemClass(name)) return false;
                        if (context.isServerClass(name)) return true;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    { 
                        //looking at system classpath
                        if (context.isParentLoaderPriority())
                            return true;
                        return false;
                    }
                });
        
        //gather together all annotations discovered
        List<DiscoveredAnnotation> annotations = new ArrayList<DiscoveredAnnotation>();
        gatherAnnotations(annotations, parser.getAnnotationHandlers());

        context.getMetaData().addDiscoveredAnnotations(annotations);           
    }
    
    
    public void parseWebInfLib (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {  
        List<FragmentDescriptor> frags = context.getMetaData().getFragments();
        
        //email from Rajiv Mordani jsrs 315 7 April 2010
        //jars that do not have a web-fragment.xml are still considered fragments
        //they have to participate in the ordering
        ArrayList<URI> webInfUris = new ArrayList<URI>();
        
        List<Resource> jars = context.getMetaData().getOrderedWebInfJars();
        
        //No ordering just use the jars in any order
        if (jars == null || jars.isEmpty())
            jars = context.getMetaData().getWebInfJars();
   
        for (Resource r : jars)
        {          
            //clear any previously discovered annotations from handlers
            clearAnnotationList(parser.getAnnotationHandlers());

            
            URI uri  = r.getURI();
            FragmentDescriptor f = getFragmentFromJar(r, frags);
           
            //if a jar has no web-fragment.xml we scan it (because it is not exluded by the ordering)
            //or if it has a fragment we scan it if it is not metadata complete
            if (f == null || !isMetaDataComplete(f))
            {
                parser.parse(uri, 
                             new ClassNameResolver()
                             {
                                 public boolean isExcluded (String name)
                                 {    
                                     if (context.isSystemClass(name)) return true;
                                     if (context.isServerClass(name)) return false;
                                     return false;
                                 }

                                 public boolean shouldOverride (String name)
                                 {
                                    //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                                    if (context.isParentLoaderPriority())
                                        return false;
                                    return true;
                                 }
                             });  
                List<DiscoveredAnnotation> annotations = new ArrayList<DiscoveredAnnotation>();
                gatherAnnotations(annotations, parser.getAnnotationHandlers());
                context.getMetaData().addDiscoveredAnnotations(r, annotations);
            }
        }
    }
     
    public void parseWebInfClasses (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        Log.debug("Scanning classes in WEB-INF/classes");
        if (context.getWebInf() != null)
        {
            Resource classesDir = context.getWebInf().addPath("classes/");
            if (classesDir.exists())
            {
                clearAnnotationList(parser.getAnnotationHandlers());
                parser.parse(classesDir, 
                             new ClassNameResolver()
                {
                    public boolean isExcluded (String name)
                    {
                        if (context.isSystemClass(name)) return true;
                        if (context.isServerClass(name)) return false;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    {
                        //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                        if (context.isParentLoaderPriority())
                            return false;
                        return true;
                    }
                });
                
                //TODO - where to set the annotations discovered from WEB-INF/classes?    
                List<DiscoveredAnnotation> annotations = new ArrayList<DiscoveredAnnotation>();
                gatherAnnotations(annotations, parser.getAnnotationHandlers());
                context.getMetaData().addDiscoveredAnnotations (annotations);
            }
        }
    }
    
 
    
    public FragmentDescriptor getFragmentFromJar (Resource jar,  List<FragmentDescriptor> frags)
    throws Exception
    {
        //check if the jar has a web-fragment.xml
        FragmentDescriptor d = null;
        for (FragmentDescriptor frag: frags)
        {
            Resource fragResource = frag.getResource(); //eg jar:file:///a/b/c/foo.jar!/META-INF/web-fragment.xml
            if (Resource.isContainedIn(fragResource,jar))
            {
                d = frag;
                break;
            }
        }
        return d;
    }
    
    
    public boolean isMetaDataComplete (WebDescriptor d)
    {
        return (d!=null && d.getMetaDataComplete() == WebDescriptor.MetaDataComplete.True);
    }
    
    protected void clearAnnotationList (List<DiscoverableAnnotationHandler> handlers)
    {
        if (handlers == null)
            return;
        
        for (DiscoverableAnnotationHandler h:handlers)
        {
            if (h instanceof AbstractDiscoverableAnnotationHandler)
                ((AbstractDiscoverableAnnotationHandler)h).resetList();
        }
    }

    protected void gatherAnnotations (List<DiscoveredAnnotation> annotations, List<DiscoverableAnnotationHandler> handlers)
    {
        for (DiscoverableAnnotationHandler h:handlers)
        {
            if (h instanceof AbstractDiscoverableAnnotationHandler)
                annotations.addAll(((AbstractDiscoverableAnnotationHandler)h).getAnnotationList());
        }
    }
}
