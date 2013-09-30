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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.annotations.AnnotationParser.Handler;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
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
    public static final String CONTAINER_INITIALIZER_STARTER = "org.eclipse.jetty.containerInitializerStarter";
    public static final String MULTI_THREADED = "org.eclipse.jetty.annotations.multiThreaded";
    public static final String MAX_SCAN_WAIT = "org.eclipse.jetty.annotations.maxWait";
    
    public static final int DEFAULT_MAX_SCAN_WAIT = 60; /* time in sec */  
    public static final boolean DEFAULT_MULTI_THREADED = true;
    
    protected List<AbstractDiscoverableAnnotationHandler> _discoverableAnnotationHandlers = new ArrayList<AbstractDiscoverableAnnotationHandler>();
    protected ClassInheritanceHandler _classInheritanceHandler;
    protected List<ContainerInitializerAnnotationHandler> _containerInitializerAnnotationHandlers = new ArrayList<ContainerInitializerAnnotationHandler>();
   
    protected List<ParserTask> _parserTasks;
    protected WebAppClassNameResolver _webAppClassNameResolver;
    protected ContainerClassNameResolver _containerClassNameResolver;
    
    
    
    public class ParserTask implements Callable<Void>
    {
        protected Exception _exception;
        protected final AnnotationParser _parser;
        protected final Set<? extends Handler> _handlers;
        protected final ClassNameResolver _resolver;
        protected final Resource _resource;
      
        
        public ParserTask (AnnotationParser parser, Set<? extends Handler>handlers, Resource resource, ClassNameResolver resolver)
        {
            _parser = parser;
            _handlers = handlers;
            _resolver = resolver;
            _resource = resource;
        }

        public Void call() throws Exception
        {
            if (_parser != null)
                _parser.parse(_handlers, _resource, _resolver); 
            return null;
        }
    }

    /**
     * WebAppClassNameResolver
     *
     * Checks to see if a classname belongs to hidden or visible packages when scanning,
     * and whether a classname that is a duplicate should override a previously
     * scanned classname. 
     * 
     * This is analogous to the management of classes that the WebAppClassLoader is doing,
     * however we don't want to load the classes at this point so we are doing it on
     * the name only.
     * 
     */
    public class WebAppClassNameResolver implements ClassNameResolver
    {
        private WebAppContext _context;

        public WebAppClassNameResolver (WebAppContext context)
        {
            _context = context;
        }

        public boolean isExcluded (String name)
        {
            if (_context.isSystemClass(name)) return true;
            if (_context.isServerClass(name)) return false;
            return false;
        }

        public boolean shouldOverride (String name)
        {
            //looking at webapp classpath, found already-parsed class 
            //of same name - did it come from system or duplicate in webapp?
            if (_context.isParentLoaderPriority())
                return false;
            return true;
        }
    }

    
    /**
     * ContainerClassNameResolver
     *
     * Checks to see if a classname belongs to a hidden or visible package
     * when scanning for annotations and thus whether it should be excluded from
     * consideration or not.
     * 
     * This is analogous to the management of classes that the WebAppClassLoader is doing,
     * however we don't want to load the classes at this point so we are doing it on
     * the name only.
     * 
     */
    public class ContainerClassNameResolver implements ClassNameResolver
    { 
        private WebAppContext _context;
        
        public ContainerClassNameResolver (WebAppContext context)
        {
            _context = context;
        }
        public boolean isExcluded (String name)
        {
            if (_context.isSystemClass(name)) return false;
            if (_context.isServerClass(name)) return true;
            return false;
        }

        public boolean shouldOverride (String name)
        {
            //visiting the container classpath, 
            if (_context.isParentLoaderPriority())
                return true;
            return false;
        }
    }
    
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        _webAppClassNameResolver = new WebAppClassNameResolver(context);
        _containerClassNameResolver = new ContainerClassNameResolver(context);
    }

   
    public void addDiscoverableAnnotationHandler(AbstractDiscoverableAnnotationHandler handler)
    {
        _discoverableAnnotationHandlers.add(handler);
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        context.removeAttribute(CLASS_INHERITANCE_MAP);
        context.removeAttribute(CONTAINER_INITIALIZERS);
        ServletContainerInitializersStarter starter = (ServletContainerInitializersStarter)context.getAttribute(CONTAINER_INITIALIZER_STARTER);
        if (starter != null)
        {
            context.removeBean(starter);
            context.removeAttribute(CONTAINER_INITIALIZER_STARTER);
        }
    }
    
    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
       context.addDecorator(new AnnotationDecorator(context));

       //Even if metadata is complete, we still need to scan for ServletContainerInitializers - if there are any
      
       if (!context.getMetaData().isMetaDataComplete())
       {
           //If metadata isn't complete, if this is a servlet 3 webapp or isConfigDiscovered is true, we need to search for annotations
           if (context.getServletContext().getEffectiveMajorVersion() >= 3 || context.isConfigurationDiscovered())
           {
               _discoverableAnnotationHandlers.add(new WebServletAnnotationHandler(context));
               _discoverableAnnotationHandlers.add(new WebFilterAnnotationHandler(context));
               _discoverableAnnotationHandlers.add(new WebListenerAnnotationHandler(context));
           }
       }

       //Regardless of metadata, if there are any ServletContainerInitializers with @HandlesTypes, then we need to scan all the
       //classes so we can call their onStartup() methods correctly
       createServletContainerInitializerAnnotationHandlers(context, getNonExcludedInitializers(context));

       if (!_discoverableAnnotationHandlers.isEmpty() || _classInheritanceHandler != null || !_containerInitializerAnnotationHandlers.isEmpty())
           scanForAnnotations(context);      
    }



    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#postConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        ConcurrentHashMap<String, ConcurrentHashSet<String>> classMap = (ConcurrentHashMap<String, ConcurrentHashSet<String>>)context.getAttribute(CLASS_INHERITANCE_MAP);
        List<ContainerInitializer> initializers = (List<ContainerInitializer>)context.getAttribute(CONTAINER_INITIALIZERS);
        
        context.removeAttribute(CLASS_INHERITANCE_MAP);
        if (classMap != null)
            classMap.clear();
        
        context.removeAttribute(CONTAINER_INITIALIZERS);
        if (initializers != null)
            initializers.clear();
        
        if (_discoverableAnnotationHandlers != null)
            _discoverableAnnotationHandlers.clear();

        _classInheritanceHandler = null;
        if (_containerInitializerAnnotationHandlers != null)
            _containerInitializerAnnotationHandlers.clear();

        if (_parserTasks != null)
        {
            _parserTasks.clear();
            _parserTasks = null;
        }
        
        super.postConfigure(context);
    }
    
    
    
    /**
     * Perform scanning of classes for annotations
     * 
     * @param context
     * @throws Exception
     */
    protected void scanForAnnotations (WebAppContext context)
    throws Exception
    {
        AnnotationParser parser = createAnnotationParser();
        boolean multiThreadedScan = isUseMultiThreading(context);
        int maxScanWait = 0;
        if (multiThreadedScan)
        {
            _parserTasks = new ArrayList<ParserTask>();
            maxScanWait = getMaxScanWait(context);
        }

        long start = 0; 
        
        if (LOG.isDebugEnabled()) 
        {
            start = System.nanoTime();
            LOG.debug("Scanning for annotations: webxml={}, metadatacomplete={}, configurationDiscovered={}, multiThreaded={}", 
                      context.getServletContext().getEffectiveMajorVersion(), 
                      context.getMetaData().isMetaDataComplete(),
                      context.isConfigurationDiscovered(),
                      multiThreadedScan);
        }
             
        parseContainerPath(context, parser);
        //email from Rajiv Mordani jsrs 315 7 April 2010
        //    If there is a <others/> then the ordering should be
        //          WEB-INF/classes the order of the declared elements + others.
        //    In case there is no others then it is
        //          WEB-INF/classes + order of the elements.
        parseWebInfClasses(context, parser);
        parseWebInfLib (context, parser); 
        
        if (!multiThreadedScan)
        {
            if (LOG.isDebugEnabled())
            {
                long end = System.nanoTime();
                LOG.debug("Annotation parsing millisec={}",(TimeUnit.MILLISECONDS.convert(end-start, TimeUnit.NANOSECONDS)));
            }
            return;
        }

        if (LOG.isDebugEnabled())
            start = System.nanoTime();
        
        //execute scan asynchronously using jetty's thread pool  
        final CountDownLatch latch = new CountDownLatch(_parserTasks.size());
        final MultiException me = new MultiException();
        final Semaphore task_limit=new Semaphore(Runtime.getRuntime().availableProcessors());
        for (final ParserTask p:_parserTasks)
        {
            task_limit.acquire();
            context.getServer().getThreadPool().execute(new Runnable()
            {
                @Override
                public void run()
                {
                   try
                   {
                       p.call();
                   }
                   catch (Exception e)
                   {
                       me.add(e);
                   }
                   finally
                   {
                       task_limit.release();
                       latch.countDown();
                   }
                }         
            });
        }
       
        boolean timeout = !latch.await(maxScanWait, TimeUnit.SECONDS);
        
        if (LOG.isDebugEnabled())
        {
            long end = System.nanoTime();
            LOG.debug("Annotation parsing millisec={}",(TimeUnit.MILLISECONDS.convert(end-start, TimeUnit.NANOSECONDS)));
        }
        
        if (timeout)
            me.add(new Exception("Timeout scanning annotations"));
        me.ifExceptionThrow();   
    }

    
    
    /**
     * @return a new AnnotationParser. This method can be overridden to use a different implementation of
     * the AnnotationParser. Note that this is considered internal API.
     */
    protected AnnotationParser createAnnotationParser()
    {
        return new AnnotationParser();
    }
    
    /**
     * Check if we should use multiple threads to scan for annotations or not
     * @param context
     * @return
     */
    protected boolean isUseMultiThreading(WebAppContext context)
    {
        //try context attribute to see if we should use multithreading
        Object o = context.getAttribute(MULTI_THREADED);
        if (o instanceof Boolean)
        {
            return ((Boolean)o).booleanValue();
        }
        //try server attribute to see if we should use multithreading
        o = context.getServer().getAttribute(MULTI_THREADED);
        if (o instanceof Boolean)
        {
            return ((Boolean)o).booleanValue();
        }
        //try system property to see if we should use multithreading
        return Boolean.valueOf(System.getProperty(MULTI_THREADED, Boolean.toString(DEFAULT_MULTI_THREADED)));
    }

   
    
    /**
     * Work out how long we should wait for the async scanning to occur.
     * 
     * @param context
     * @return
     */
    protected int getMaxScanWait (WebAppContext context)
    {
        //try context attribute to get max time in sec to wait for scan completion
        Object o = context.getAttribute(MAX_SCAN_WAIT);
        if (o != null && o instanceof Number)
        {
            return ((Number)o).intValue();
        }
        //try server attribute to get max time in sec to wait for scan completion
        o = context.getServer().getAttribute(MAX_SCAN_WAIT);
        if (o != null && o instanceof Number)
        {
            return ((Number)o).intValue();
        }
        //try system property to get max time in sec to wait for scan completion
        return Integer.getInteger(MAX_SCAN_WAIT, DEFAULT_MAX_SCAN_WAIT).intValue();
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
            ContainerInitializer initializer = null;
            if (annotation != null)
            {    
                //There is a HandlesTypes annotation on the on the ServletContainerInitializer
                Class[] classes = annotation.value();
                if (classes != null)
                {
                    initializer = new ContainerInitializer(service, classes);

                    //If we haven't already done so, we need to register a handler that will
                    //process the whole class hierarchy to satisfy the ServletContainerInitializer
                    if (context.getAttribute(CLASS_INHERITANCE_MAP) == null)
                    {
                        //MultiMap<String> map = new MultiMap<>();
                        ConcurrentHashMap<String, ConcurrentHashSet<String>> map = new ConcurrentHashMap<String, ConcurrentHashSet<String>>();
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
                {
                    initializer = new ContainerInitializer(service, null);
                    if (LOG.isDebugEnabled()) LOG.debug("No classes in HandlesTypes on initializer "+service.getClass());
                }
            }
            else
            {
                initializer = new ContainerInitializer(service, null);
                if (LOG.isDebugEnabled()) LOG.debug("No annotation on initializer "+service.getClass());
            }
            
            initializers.add(initializer);
        }
        
        
        //add a bean to the context which will call the servletcontainerinitializers when appropriate
        ServletContainerInitializersStarter starter = (ServletContainerInitializersStarter)context.getAttribute(CONTAINER_INITIALIZER_STARTER);
        if (starter != null)
            throw new IllegalStateException("ServletContainerInitializersStarter already exists");
        starter = new ServletContainerInitializersStarter(context);
        context.setAttribute(CONTAINER_INITIALIZER_STARTER, starter);
        context.addBean(starter, true);
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
        long start = 0;
        if (LOG.isDebugEnabled())
            start = System.nanoTime();
        ServiceLoader<ServletContainerInitializer> loadedInitializers = ServiceLoader.load(ServletContainerInitializer.class, context.getClassLoader());
        if (LOG.isDebugEnabled())
            LOG.debug("Service loaders found in {}ms", (TimeUnit.MILLISECONDS.convert((System.nanoTime()-start), TimeUnit.NANOSECONDS)));
        
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
    public void parseContainerPath (final WebAppContext context, final AnnotationParser parser) throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        LOG.debug("Scanning container jars");   
        
        //always parse for discoverable annotations as well as class hierarchy and servletcontainerinitializer related annotations
        final Set<Handler> handlers = new HashSet<Handler>();
        handlers.addAll(_discoverableAnnotationHandlers);
        handlers.addAll(_containerInitializerAnnotationHandlers);
        if (_classInheritanceHandler != null)
            handlers.add(_classInheritanceHandler);

        for (Resource r : context.getMetaData().getContainerResources())
        {
            //queue it up for scanning if using multithreaded mode
            if (_parserTasks != null)
                _parserTasks.add(new ParserTask(parser, handlers, r, _containerClassNameResolver));  
            else
                //just scan it now
                parser.parse(handlers, r, _containerClassNameResolver);
        } 
    }


    /**
     * Scan jars in WEB-INF/lib
     * 
     * @param context
     * @param parser
     * @throws Exception
     */
    public void parseWebInfLib (final WebAppContext context, final AnnotationParser parser) throws Exception
    {   
        LOG.debug("Scanning WEB-INF/lib jars");
        
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
            final Set<Handler> handlers = new HashSet<Handler>();

            FragmentDescriptor f = getFragmentFromJar(r, frags);

            //if its from a fragment jar that is metadata complete, we should skip scanning for @webservlet etc
            // but yet we still need to do the scanning for the classes on behalf of  the servletcontainerinitializers
            //if a jar has no web-fragment.xml we scan it (because it is not excluded by the ordering)
            //or if it has a fragment we scan it if it is not metadata complete
            if (f == null || !isMetaDataComplete(f) || _classInheritanceHandler != null ||  !_containerInitializerAnnotationHandlers.isEmpty())
            {
                //register the classinheritance handler if there is one
                if (_classInheritanceHandler != null)
                    handlers.add(_classInheritanceHandler);

                //register the handlers for the @HandlesTypes values that are themselves annotations if there are any
                handlers.addAll(_containerInitializerAnnotationHandlers);

                //only register the discoverable annotation handlers if this fragment is not metadata complete, or has no fragment descriptor
                if (f == null || !isMetaDataComplete(f))
                    handlers.addAll(_discoverableAnnotationHandlers);

                if (_parserTasks != null)
                    _parserTasks.add (new ParserTask(parser, handlers,r, _webAppClassNameResolver));
                else
                    parser.parse(handlers, r, _webAppClassNameResolver);
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
        LOG.debug("Scanning WEB-INF/classes");
       
        Set<Handler> handlers = new HashSet<Handler>();
        handlers.addAll(_discoverableAnnotationHandlers);
        if (_classInheritanceHandler != null)
            handlers.add(_classInheritanceHandler);
        handlers.addAll(_containerInitializerAnnotationHandlers);

        for (Resource dir : context.getMetaData().getWebInfClassesDirs())
        {
            if (_parserTasks != null)
                _parserTasks.add(new ParserTask(parser, handlers, dir, _webAppClassNameResolver));
            else
                parser.parse(handlers, dir, _webAppClassNameResolver);
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
