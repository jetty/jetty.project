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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import org.eclipse.jetty.util.statistic.CounterStatistic;
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
    
    public static final String SERVLET_CONTAINER_INITIALIZER_ORDER = "org.eclipse.jetty.containerInitializerOrder";
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

    protected CounterStatistic _containerPathStats;
    protected CounterStatistic _webInfLibStats;
    protected CounterStatistic _webInfClassesStats;
  
    /**
     * TimeStatistic
     *
     * Simple class to capture elapsed time of an operation.
     * 
     */
    public class TimeStatistic 
    {
        public long _start = 0;
        public long _end = 0;
        
        public void start ()
        {
            _start = System.nanoTime();
        }
        
        public void end ()
        {
            _end = System.nanoTime();
        }
        
        public long getStart()
        {
            return _start;
        }
        
        public long getEnd ()
        {
            return _end;
        }
        
        public long getElapsed ()
        {
            return (_end > _start?(_end-_start):0);
        }
    }
    
    
    /**
     * ParserTask
     *
     * Task to executing scanning of a resource for annotations.
     * 
     */
    public class ParserTask implements Callable<Void>
    {
        protected Exception _exception;
        protected final AnnotationParser _parser;
        protected final Set<? extends Handler> _handlers;
        protected final ClassNameResolver _resolver;
        protected final Resource _resource;
        protected TimeStatistic _stat;
        
        public ParserTask (AnnotationParser parser, Set<? extends Handler>handlers, Resource resource, ClassNameResolver resolver)
        {
            _parser = parser;
            _handlers = handlers;
            _resolver = resolver;
            _resource = resource;
        }
        
        public void setStatistic(TimeStatistic stat)
        {
           _stat = stat; 
        }

        public Void call() throws Exception
        {            
            if (_stat != null)
                _stat.start();
            if (_parser != null)
                _parser.parse(_handlers, _resource, _resolver); 
            if (_stat != null)
                _stat.end();
            return null;
        }
        
        public TimeStatistic getStatistic()
        {
            return _stat;
        }
        
        public Resource getResource()
        {
            return _resource;
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
    
    
    /**
     * ServletContainerInitializerOrdering
     *
     * A list of classnames of ServletContainerInitializers in the order in which
     * they are to be called back. One name only in the list can be "*", which is a
     * wildcard which matches any other ServletContainerInitializer name not already
     * matched.
     */
    public class ServletContainerInitializerOrdering 
    {
        private Map<String, Integer> _indexMap = new HashMap<String, Integer>();
        private Integer _star = null;
        private String _ordering = null;
        
        public ServletContainerInitializerOrdering (String ordering)
        {
            if (ordering != null)
            {
                _ordering = ordering;
                
                String[] tmp = ordering.split(",");
                
                for (int i=0; i<tmp.length; i++)
                {
                    String s = tmp[i].trim();
                    _indexMap.put(s, Integer.valueOf(i));
                    if ("*".equals(s))
                    {
                        if (_star != null)
                            throw new IllegalArgumentException("Duplicate wildcards in ServletContainerInitializer ordering "+ordering);
                        _star = Integer.valueOf(i);
                    }
                    
                }
            }
        }
        
        /**
         * True if "*" is one of the values.
         * @return
         */
        public boolean hasWildcard()
        {
            return _star != null;
        }
        
        /**
         * Get the index of the "*" element, if it is specified. -1 otherwise.
         * @return
         */
        public int getWildcardIndex()
        {
            if (!hasWildcard())
                return -1;
            return _star.intValue();
        }
        
        /**
         * True if the ordering contains a single value of "*"
         * @return
         */
        public boolean isDefaultOrder ()
        {
            return (getSize() == 1 && hasWildcard());
        }
        
        /**
         * Get the order index of the given classname
         * @param name
         * @return
         */
        public int getIndexOf (String name)
        {
            Integer i = _indexMap.get(name);
            if (i == null)
                return -1;
            return i.intValue();
        }
        
        /**
         * Get the number of elements of the ordering
         * @return
         */
        public int getSize()
        {
            return _indexMap.size();
        }
        
        public String toString()
        {
            if (_ordering == null)
                return "";
            return _ordering;
        }
    }
    
    
    
    /**
     * ServletContainerInitializerComparator
     *
     * Comparator impl that orders a set of ServletContainerInitializers according to the
     * list of classnames (optionally containing a "*" wildcard character) established in a
     * ServletContainerInitializerOrdering.
     * @see ServletContainerInitializerOrdering
     */
    public class ServletContainerInitializerComparator implements Comparator<ServletContainerInitializer>
    {
        private ServletContainerInitializerOrdering _ordering;
        
        
        public ServletContainerInitializerComparator (ServletContainerInitializerOrdering ordering)
        {
            _ordering = ordering;
        }

        @Override
        public int compare(ServletContainerInitializer sci1, ServletContainerInitializer sci2)
        {
            String c1 = (sci1 != null? sci1.getClass().getName() : null);
            String c2 = (sci2 != null? sci2.getClass().getName() : null);

            if (c1 == null && c2 == null)
                return 0;
            
            int i1 = _ordering.getIndexOf(c1);
            if (i1 < 0 && _ordering.hasWildcard())
                i1 = _ordering.getWildcardIndex();
            int i2 = _ordering.getIndexOf(c2);
            if (i2 < 0 && _ordering.hasWildcard())
                i2 = _ordering.getWildcardIndex();
           
            return Integer.compare(i1, i2);
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
       
       // Resolve container initializers
       List<ContainerInitializer> initializers = 
    		   (List<ContainerInitializer>)context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZERS);
       if (initializers != null && initializers.size()>0)
       {
           Map<String, Set<String>> map = ( Map<String, Set<String>>) context.getAttribute(AnnotationConfiguration.CLASS_INHERITANCE_MAP);
           if (map == null)
               LOG.warn ("ServletContainerInitializers: detected. Class hierarchy: empty");
           for (ContainerInitializer i : initializers)
        	   i.resolveClasses(context,map);
       }
    }


    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#postConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        ConcurrentHashMap<String, ConcurrentHashSet<String>> classMap = (ClassInheritanceMap)context.getAttribute(CLASS_INHERITANCE_MAP);
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
        _parserTasks = new ArrayList<ParserTask>();

        long start = 0; 


        if (LOG.isDebugEnabled())
            LOG.debug("Annotation scanning commencing: webxml={}, metadatacomplete={}, configurationDiscovered={}, multiThreaded={}, maxScanWait={}", 
                      context.getServletContext().getEffectiveMajorVersion(), 
                      context.getMetaData().isMetaDataComplete(),
                      context.isConfigurationDiscovered(),
                      isUseMultiThreading(context),
                      getMaxScanWait(context));

             
        parseContainerPath(context, parser);
        //email from Rajiv Mordani jsrs 315 7 April 2010
        //    If there is a <others/> then the ordering should be
        //          WEB-INF/classes the order of the declared elements + others.
        //    In case there is no others then it is
        //          WEB-INF/classes + order of the elements.
        parseWebInfClasses(context, parser);
        parseWebInfLib (context, parser); 
        
        start = System.nanoTime();
        
        //execute scan, either effectively synchronously (1 thread only), or asynchronously (limited by number of processors available) 
        final Semaphore task_limit = (isUseMultiThreading(context)? new Semaphore(Runtime.getRuntime().availableProcessors()):new Semaphore(1));     
        final CountDownLatch latch = new CountDownLatch(_parserTasks.size());
        final MultiException me = new MultiException();
    
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
       
        boolean timeout = !latch.await(getMaxScanWait(context), TimeUnit.SECONDS);
          
        if (LOG.isDebugEnabled())
        {
            for (ParserTask p:_parserTasks)
                LOG.debug("Scanned {} in {}ms", p.getResource(), TimeUnit.MILLISECONDS.convert(p.getStatistic().getElapsed(), TimeUnit.NANOSECONDS));

            LOG.debug("Scanned {} container path jars, {} WEB-INF/lib jars, {} WEB-INF/classes dirs in {}ms for context {}",
                    _containerPathStats.getTotal(), _webInfLibStats.getTotal(), _webInfClassesStats.getTotal(),
                    (TimeUnit.MILLISECONDS.convert(System.nanoTime()-start, TimeUnit.NANOSECONDS)),
                    context);
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
                Class<?>[] classes = annotation.value();
                if (classes != null)
                {
                    initializer = new ContainerInitializer(service, classes);

                    //If we haven't already done so, we need to register a handler that will
                    //process the whole class hierarchy to satisfy the ServletContainerInitializer
                    if (context.getAttribute(CLASS_INHERITANCE_MAP) == null)
                    {
                        //MultiMap<String> map = new MultiMap<>();
                        ConcurrentHashMap<String, ConcurrentHashSet<String>> map = new ClassInheritanceMap();
                        context.setAttribute(CLASS_INHERITANCE_MAP, map);
                        _classInheritanceHandler = new ClassInheritanceHandler(map);
                    }

                    for (Class<?> c: classes)
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

    
    public Resource getJarFor (ServletContainerInitializer service) 
    throws MalformedURLException, IOException
    {
        String loadingJarName = Thread.currentThread().getContextClassLoader().getResource(service.getClass().getName().replace('.','/')+".class").toString();

        int i = loadingJarName.indexOf(".jar");
        if (i < 0)
            return null; //not from a jar
        
        loadingJarName = loadingJarName.substring(0,i+4);
        loadingJarName = (loadingJarName.startsWith("jar:")?loadingJarName.substring(4):loadingJarName);
        return Resource.newResource(loadingJarName);
    }
    

    /**
     * Check to see if the ServletContainerIntializer loaded via the ServiceLoader came
     * from a jar that is excluded by the fragment ordering. See ServletSpec 3.0 p.85.
     * @param context
     * @param sci
     * @return true if excluded
     */
    public boolean isFromExcludedJar (WebAppContext context, ServletContainerInitializer sci, Resource sciResource)
    throws Exception
    {
        if (sci == null)
            throw new IllegalArgumentException("ServletContainerInitializer null");
        if (context == null)
            throw new IllegalArgumentException("WebAppContext null");
        
        //A ServletContainerInitializer that came from the container's classpath cannot be excluded by an ordering
        //of WEB-INF/lib jars
        if (sci.getClass().getClassLoader()==context.getClassLoader().getParent())
            return false;
        
        List<Resource> orderedJars = context.getMetaData().getOrderedWebInfJars();

        //If no ordering, nothing is excluded
        if (context.getMetaData().getOrdering() == null)
            return false;

        //there is an ordering, but there are no jars resulting from the ordering, everything excluded
        if (orderedJars.isEmpty())
            return true;

        if (sciResource == null)
            return false; //not from a jar therefore not from WEB-INF so not excludable
        
        URI loadingJarURI = sciResource.getURI();
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
        ArrayList<ServletContainerInitializer> nonExcludedInitializers = new ArrayList<ServletContainerInitializer>();

        
        //We use the ServiceLoader mechanism to find the ServletContainerInitializer classes to inspect
        long start = 0;

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        ServiceLoader<ServletContainerInitializer> loadedInitializers = null;
        try
        {        
            if (LOG.isDebugEnabled())
                start = System.nanoTime();
            Thread.currentThread().setContextClassLoader(context.getClassLoader());
            loadedInitializers = ServiceLoader.load(ServletContainerInitializer.class);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug("Service loaders found in {}ms", (TimeUnit.MILLISECONDS.convert((System.nanoTime()-start), TimeUnit.NANOSECONDS)));

        ServletContainerInitializerOrdering initializerOrdering = getInitializerOrdering(context);
       
        if (initializerOrdering != null && !initializerOrdering.isDefaultOrder())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ordering ServletContainerInitializers with "+initializerOrdering);
            
            //There is an ordering that is not just "*".
            //Arrange ServletContainerInitializers according to the ordering of classnames given, irrespective of coming from container or webapp classpaths
            for (ServletContainerInitializer sci:loadedInitializers)
            {
                Resource sciResource = getJarFor(sci);
                if (!isFromExcludedJar(context, sci, sciResource))
                {
                    String name = sci.getClass().getName();
                    if (initializerOrdering.getIndexOf(name) >= 0 || initializerOrdering.hasWildcard())
                        nonExcludedInitializers.add(sci);
                }
            }
      
            //apply the ordering
            Collections.sort(nonExcludedInitializers, new ServletContainerInitializerComparator(initializerOrdering));
        }
        else
        {
            //No ordering specified, or just the wildcard value "*" specified.
            //Fallback to ordering the ServletContainerInitializers according to:
            //container classpath first, WEB-INF/clases then WEB-INF/lib (obeying any web.xml jar ordering)
            if (LOG.isDebugEnabled())
                LOG.debug("Ordering ServletContainerInitializers as container path, webapp path");
            
            Map<ServletContainerInitializer,Resource> webappPathInitializerResourceMap = new HashMap<ServletContainerInitializer,Resource>();
            for (ServletContainerInitializer sci : loadedInitializers)
            {
                //if its on the container's classpath then add it
                if (sci.getClass().getClassLoader() == context.getClassLoader().getParent())
                {
                    nonExcludedInitializers.add(sci);
                }
                else
                {
                    //if on the webapp's classpath then check the containing jar is not excluded from consideration
                    Resource sciResource = getJarFor(sci);        
                    if (!isFromExcludedJar(context, sci, sciResource))
                    {   
                        webappPathInitializerResourceMap.put(sci, sciResource);                       
                    }
                }
            }            

            //add the webapp classpath ones according to any web.xml ordering
            if (context.getMetaData().getOrdering() == null)
                nonExcludedInitializers.addAll(webappPathInitializerResourceMap.keySet()); //no ordering, just add them
            else
            {
                //add in any ServletContainerInitializers which are not in a jar, as they must be from WEB-INF/classes
                for (Map.Entry<ServletContainerInitializer, Resource> entry:webappPathInitializerResourceMap.entrySet())
                {
                    if (entry.getValue() == null)
                        nonExcludedInitializers.add(entry.getKey());
                }
                
                //add ServletContainerInitializers according to the ordering of its containing jar
                for (Resource webInfJar:context.getMetaData().getOrderedWebInfJars())
                {
                    for (Map.Entry<ServletContainerInitializer, Resource> entry:webappPathInitializerResourceMap.entrySet())
                    {
                        if (webInfJar.equals(entry.getValue()))
                            nonExcludedInitializers.add(entry.getKey());
                    }
                }
            }
        }   
        
        return nonExcludedInitializers;
    }


    /**
     * Jetty-specific extension that allows an ordering to be applied across ALL ServletContainerInitializers.
     * 
     * @return
     */
    public ServletContainerInitializerOrdering getInitializerOrdering (WebAppContext context)
    {
        if (context == null)
            return null;
        
        String tmp = (String)context.getAttribute(SERVLET_CONTAINER_INITIALIZER_ORDER);
        if (tmp == null || "".equals(tmp.trim()))
            return null;
        
        return new ServletContainerInitializerOrdering(tmp);
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
        //always parse for discoverable annotations as well as class hierarchy and servletcontainerinitializer related annotations
        final Set<Handler> handlers = new HashSet<Handler>();
        handlers.addAll(_discoverableAnnotationHandlers);
        handlers.addAll(_containerInitializerAnnotationHandlers);
        if (_classInheritanceHandler != null)
            handlers.add(_classInheritanceHandler);

        _containerPathStats = new CounterStatistic();

        for (Resource r : context.getMetaData().getContainerResources())
        {
            //queue it up for scanning if using multithreaded mode
            if (_parserTasks != null)
            {
                ParserTask task = new ParserTask(parser, handlers, r, _containerClassNameResolver);
                _parserTasks.add(task);  
                _containerPathStats.increment();
                if (LOG.isDebugEnabled())
                    task.setStatistic(new TimeStatistic());
            }
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
        List<FragmentDescriptor> frags = context.getMetaData().getFragments();

        //email from Rajiv Mordani jsrs 315 7 April 2010
        //jars that do not have a web-fragment.xml are still considered fragments
        //they have to participate in the ordering
        ArrayList<URI> webInfUris = new ArrayList<URI>();

        List<Resource> jars = context.getMetaData().getOrderedWebInfJars();

        //No ordering just use the jars in any order
        if (jars == null || jars.isEmpty())
            jars = context.getMetaData().getWebInfJars();

        _webInfLibStats = new CounterStatistic();

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
                {
                    ParserTask task = new ParserTask(parser, handlers,r, _webAppClassNameResolver);
                    _parserTasks.add (task);
                    _webInfLibStats.increment();
                    if (LOG.isDebugEnabled())
                        task.setStatistic(new TimeStatistic());
                }
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
        Set<Handler> handlers = new HashSet<Handler>();
        handlers.addAll(_discoverableAnnotationHandlers);
        if (_classInheritanceHandler != null)
            handlers.add(_classInheritanceHandler);
        handlers.addAll(_containerInitializerAnnotationHandlers);

        _webInfClassesStats = new CounterStatistic();

        for (Resource dir : context.getMetaData().getWebInfClassesDirs())
        {
            if (_parserTasks != null)
            {
                ParserTask task = new ParserTask(parser, handlers, dir, _webAppClassNameResolver);
                _parserTasks.add(task);
                _webInfClassesStats.increment();
                if (LOG.isDebugEnabled())
                    task.setStatistic(new TimeStatistic());
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

    public static class ClassInheritanceMap extends ConcurrentHashMap<String, ConcurrentHashSet<String>>
    {
        
        @Override
        public String toString()
        {
            return String.format("ClassInheritanceMap@%x{size=%d}",hashCode(),size());
        }
    }
}


