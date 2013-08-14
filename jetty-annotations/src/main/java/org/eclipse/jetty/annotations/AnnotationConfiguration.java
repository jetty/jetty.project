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

import java.net.URI;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.MetaDataComplete;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;

/**
 * Configuration for Annotations
 *
 *
 */
public class AnnotationConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(AnnotationConfiguration.class);
    public static final String CLASS_INHERITANCE_MAP  = "org.eclipse.jetty.classInheritanceMap";
    public static final String CONTAINER_INITIALIZERS = "org.eclipse.jetty.containerInitializers";
    public static final String CONTAINER_INITIALIZER_LISTENER = "org.eclipse.jetty.containerInitializerListener";
  
    
    protected List<DiscoverableAnnotationHandler> _discoverableAnnotationHandlers = new ArrayList<DiscoverableAnnotationHandler>();
    protected ClassInheritanceHandler _classInheritanceHandler;
    protected List<ContainerInitializerAnnotationHandler> _containerInitializerAnnotationHandlers = new ArrayList<ContainerInitializerAnnotationHandler>();
   
    
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
    }

   
    public void addDiscoverableAnnotationHandler(DiscoverableAnnotationHandler handler)
    {
        _discoverableAnnotationHandlers.add(handler);
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        context.removeAttribute(CLASS_INHERITANCE_MAP);
        context.removeAttribute(CONTAINER_INITIALIZERS);
        ServletContainerInitializerListener listener = (ServletContainerInitializerListener)context.getAttribute(CONTAINER_INITIALIZER_LISTENER);
        if (listener != null)
        {
            context.removeBean(listener);
            context.removeAttribute(CONTAINER_INITIALIZER_LISTENER);
        }
    }
    
    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
       boolean metadataComplete = context.getMetaData().isMetaDataComplete();
       context.addDecorator(new AnnotationDecorator(context));


       //Even if metadata is complete, we still need to scan for ServletContainerInitializers - if there are any
       AnnotationParser parser = null;
       if (!metadataComplete)
       {
           //If metadata isn't complete, if this is a servlet 3 webapp or isConfigDiscovered is true, we need to search for annotations
           if (context.getServletContext().getEffectiveMajorVersion() >= 3 || context.isConfigurationDiscovered())
           {
               _discoverableAnnotationHandlers.add(new WebServletAnnotationHandler(context));
               _discoverableAnnotationHandlers.add(new WebFilterAnnotationHandler(context));
               _discoverableAnnotationHandlers.add(new WebListenerAnnotationHandler(context));
           }
       }
       else
           if (LOG.isDebugEnabled()) LOG.debug("Metadata-complete==true,  not processing discoverable servlet annotations for context "+context);



       //Regardless of metadata, if there are any ServletContainerInitializers with @HandlesTypes, then we need to scan all the
       //classes so we can call their onStartup() methods correctly
       createServletContainerInitializerAnnotationHandlers(context, getNonExcludedInitializers(context));

       if (!_discoverableAnnotationHandlers.isEmpty() || _classInheritanceHandler != null || !_containerInitializerAnnotationHandlers.isEmpty())
       {
           parser = createAnnotationParser();
           if (LOG.isDebugEnabled()) LOG.debug("Scanning all classses for annotations: webxmlVersion="+context.getServletContext().getEffectiveMajorVersion()+" configurationDiscovered="+context.isConfigurationDiscovered());
           parseContainerPath(context, parser);
           //email from Rajiv Mordani jsrs 315 7 April 2010
           //    If there is a <others/> then the ordering should be
           //          WEB-INF/classes the order of the declared elements + others.
           //    In case there is no others then it is
           //          WEB-INF/classes + order of the elements.
           parseWebInfClasses(context, parser);
           parseWebInfLib (context, parser);
           
           for (DiscoverableAnnotationHandler h:_discoverableAnnotationHandlers)
               context.getMetaData().addDiscoveredAnnotations(((AbstractDiscoverableAnnotationHandler)h).getAnnotationList());      
       }
    }



    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#postConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        MultiMap<String> map = (MultiMap<String>)context.getAttribute(CLASS_INHERITANCE_MAP);
        if (map != null)
            map.clear();
        
        context.removeAttribute(CLASS_INHERITANCE_MAP);
        
        List<ContainerInitializer> initializers = (List<ContainerInitializer>)context.getAttribute(CONTAINER_INITIALIZERS);
        if (initializers != null)
            initializers.clear();
        if (_discoverableAnnotationHandlers != null)
            _discoverableAnnotationHandlers.clear();
      
        _classInheritanceHandler = null;
        if (_containerInitializerAnnotationHandlers != null)
            _containerInitializerAnnotationHandlers.clear();
  
        super.postConfigure(context);
    }

    /**
     * @return a new AnnotationParser. This method can be overridden to use a different impleemntation of
     * the AnnotationParser. Note that this is considered internal API.
     */
    protected AnnotationParser createAnnotationParser()
    {
        return new AnnotationParser();
    }

    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#cloneConfigure(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
        context.addDecorator(new AnnotationDecorator(context));
    }


    
    /**
     * @param context
     * @param scis
     * @throws Exception
     */
    public void createServletContainerInitializerAnnotationHandlers (WebAppContext context, List<ServletContainerInitializer> scis)
    throws Exception
    {


        if (scis == null || scis.isEmpty())
            return; // nothing to do
        


        List<ContainerInitializer> initializers = new ArrayList<ContainerInitializer>();
        context.setAttribute(CONTAINER_INITIALIZERS, initializers);

        for (ServletContainerInitializer service : scis)
        {
            HandlesTypes annotation = service.getClass().getAnnotation(HandlesTypes.class);
            ContainerInitializer initializer = new ContainerInitializer();
            initializer.setTarget(service);
            initializers.add(initializer);
            if (annotation != null)
            {
                //There is a HandlesTypes annotation on the on the ServletContainerInitializer
                Class[] classes = annotation.value();
                if (classes != null)
                {
                    initializer.setInterestedTypes(classes);


                    //If we haven't already done so, we need to register a handler that will
                    //process the whole class hierarchy to satisfy the ServletContainerInitializer
                    if (context.getAttribute(CLASS_INHERITANCE_MAP) == null)
                    {
                        MultiMap<String> map = new MultiMap<>();
                        context.setAttribute(CLASS_INHERITANCE_MAP, map);
                        _classInheritanceHandler = new ClassInheritanceHandler(map);
                    }

                    for (Class c: classes)
                    {
                        //The value of one of the HandlesTypes classes is actually an Annotation itself so
                        //register a handler for it
                        if (c.isAnnotation())
                        {
                            if (LOG.isDebugEnabled()) LOG.debug("Registering annotation handler for "+c.getName());

                           _containerInitializerAnnotationHandlers.add(new ContainerInitializerAnnotationHandler(initializer, c));
                        }
                    }
                }
                else
                    if (LOG.isDebugEnabled()) LOG.debug("No classes in HandlesTypes on initializer "+service.getClass());
            }
            else
                if (LOG.isDebugEnabled()) LOG.debug("No annotation on initializer "+service.getClass());
        }



        //add a bean which will call the servletcontainerinitializers when appropriate
        ServletContainerInitializerListener listener = (ServletContainerInitializerListener)context.getAttribute(CONTAINER_INITIALIZER_LISTENER);
        if (listener != null)
            throw new IllegalStateException("ServletContainerInitializerListener already exists");
        listener = new ServletContainerInitializerListener();
        listener.setWebAppContext(context);
        context.setAttribute(CONTAINER_INITIALIZER_LISTENER, listener);
        context.addBean(listener, true);
    }



    /**
     * Check to see if the ServletContainerIntializer loaded via the ServiceLoader came
     * from a jar that is excluded by the fragment ordering. See ServletSpec 3.0 p.85.
     * @param context
     * @param service
     * @return true if excluded
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



    /**
     * @param context
     * @return list of non-excluded {@link ServletContainerInitializer}s
     * @throws Exception
     */
    public List<ServletContainerInitializer> getNonExcludedInitializers (WebAppContext context)
    throws Exception
    {
        List<ServletContainerInitializer> nonExcludedInitializers = new ArrayList<ServletContainerInitializer>();

        //We use the ServiceLoader mechanism to find the ServletContainerInitializer classes to inspect
        ServiceLoader<ServletContainerInitializer> loadedInitializers = ServiceLoader.load(ServletContainerInitializer.class, context.getClassLoader());

        if (loadedInitializers != null)
        {
            for (ServletContainerInitializer service : loadedInitializers)
            {
                if (!isFromExcludedJar(context, service))
                    nonExcludedInitializers.add(service);
            }
        }
        return nonExcludedInitializers;
    }




    /**
     * Scan jars on container path.
     * 
     * @param context
     * @param parser
     * @throws Exception
     */
    public void parseContainerPath (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        LOG.debug("Scanning container jars");

        //always parse for discoverable annotations as well as class hierarchy and servletcontainerinitializer related annotations
        parser.clearHandlers();
        for (DiscoverableAnnotationHandler h:_discoverableAnnotationHandlers)
        {
            if (h instanceof AbstractDiscoverableAnnotationHandler)
                ((AbstractDiscoverableAnnotationHandler)h).setResource(null); //
        }
        parser.registerHandlers(_discoverableAnnotationHandlers);
        parser.registerHandler(_classInheritanceHandler);
        parser.registerHandlers(_containerInitializerAnnotationHandlers);

        //Convert from Resource to URI
        ArrayList<URI> containerUris = new ArrayList<URI>();
        for (Resource r : context.getMetaData().getContainerResources())
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

         
    }


    /**
     * Scan jars in WEB-INF/lib
     * 
     * @param context
     * @param parser
     * @throws Exception
     */
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
            //for each jar, we decide which set of annotations we need to parse for
            parser.clearHandlers();

            URI uri  = r.getURI();
            FragmentDescriptor f = getFragmentFromJar(r, frags);

            //if its from a fragment jar that is metadata complete, we should skip scanning for @webservlet etc
            // but yet we still need to do the scanning for the classes on behalf of  the servletcontainerinitializers
            //if a jar has no web-fragment.xml we scan it (because it is not excluded by the ordering)
            //or if it has a fragment we scan it if it is not metadata complete
            if (f == null || !isMetaDataComplete(f) || _classInheritanceHandler != null ||  !_containerInitializerAnnotationHandlers.isEmpty())
            {
                //register the classinheritance handler if there is one
                parser.registerHandler(_classInheritanceHandler);
                
                //register the handlers for the @HandlesTypes values that are themselves annotations if there are any
                parser.registerHandlers(_containerInitializerAnnotationHandlers);
                
                //only register the discoverable annotation handlers if this fragment is not metadata complete, or has no fragment descriptor
                if (f == null || !isMetaDataComplete(f))
                {
                    for (DiscoverableAnnotationHandler h:_discoverableAnnotationHandlers)
                    {
                        if (h instanceof AbstractDiscoverableAnnotationHandler)
                            ((AbstractDiscoverableAnnotationHandler)h).setResource(r);
                    }
                    parser.registerHandlers(_discoverableAnnotationHandlers);
                }

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
            }
        }
    }

    /**
     * Scan classes in WEB-INF/classes
     * 
     * @param context
     * @param parser
     * @throws Exception
     */
    public void parseWebInfClasses (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        LOG.debug("Scanning classes in WEB-INF/classes");
        if (context.getWebInf() != null)
        {
            Resource classesDir = context.getWebInf().addPath("classes/");
            if (classesDir.exists())
            {
                parser.clearHandlers();
                for (DiscoverableAnnotationHandler h:_discoverableAnnotationHandlers)
                {
                    if (h instanceof AbstractDiscoverableAnnotationHandler)
                        ((AbstractDiscoverableAnnotationHandler)h).setResource(null); //
                }
                parser.registerHandlers(_discoverableAnnotationHandlers);
                parser.registerHandler(_classInheritanceHandler);
                parser.registerHandlers(_containerInitializerAnnotationHandlers);
                
                parser.parseDir(classesDir,
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
                                        if (context.isParentLoaderPriority()) return false;
                                        return true;
                                    }
                                });
            }
        }
    }



    /**
     * Get the web-fragment.xml from a jar
     * 
     * @param jar
     * @param frags
     * @return the fragment if found, or null of not found
     * @throws Exception
     */
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
        return (d!=null && d.getMetaDataComplete() == MetaDataComplete.True);
    }


}
