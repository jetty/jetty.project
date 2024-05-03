//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.annotations;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.annotation.HandlesTypes;
import org.eclipse.jetty.ee.Source;
import org.eclipse.jetty.ee.Source.Origin;
import org.eclipse.jetty.ee11.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee11.servlet.ServletContainerInitializerHolder;
import org.eclipse.jetty.ee11.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee11.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee11.webapp.FragmentDescriptor;
import org.eclipse.jetty.ee11.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee11.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee11.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.ee11.webapp.WebDescriptor;
import org.eclipse.jetty.ee11.webapp.WebXmlConfiguration;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for Annotations
 */
public class AnnotationConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationConfiguration.class);

    public static final String SERVLET_CONTAINER_INITIALIZER_EXCLUSION_PATTERN = "org.eclipse.jetty.containerInitializerExclusionPattern";
    public static final String SERVLET_CONTAINER_INITIALIZER_ORDER = "org.eclipse.jetty.containerInitializerOrder";
    public static final String CLASS_INHERITANCE_MAP = "org.eclipse.jetty.classInheritanceMap";
    public static final String CONTAINER_INITIALIZERS = "org.eclipse.jetty.containerInitializers";
    public static final String CONTAINER_INITIALIZER_STARTER = "org.eclipse.jetty.containerInitializerStarter";
    public static final String MULTI_THREADED = "org.eclipse.jetty.annotations.multiThreaded";
    public static final String MAX_SCAN_WAIT = "org.eclipse.jetty.annotations.maxWait";
    protected static final String STATE = "org.eclipse.jetty.annotations.state";

    public static final int DEFAULT_MAX_SCAN_WAIT = 60; /* time in sec */
    public static final boolean DEFAULT_MULTI_THREADED = true;

    protected static class State
    {
        State(WebAppContext context)
        {
            _context = context;
        }

        public final WebAppContext _context;
        public final List<AbstractDiscoverableAnnotationHandler> _discoverableAnnotationHandlers = new ArrayList<>();
        public final List<ContainerInitializerAnnotationHandler> _containerInitializerAnnotationHandlers = new ArrayList<>();
        public final List<DiscoveredServletContainerInitializerHolder> _sciHolders = new ArrayList<>();
        public ClassInheritanceHandler _classInheritanceHandler;
        public List<ParserTask> _parserTasks;
        public CounterStatistic _containerPathStats;
        public CounterStatistic _webInfLibStats;
        public CounterStatistic _webInfClassesStats;
        public Pattern _sciExcludePattern;
    }

    public AnnotationConfiguration()
    {
        super(new Builder()
            .addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, FragmentConfiguration.class, PlusConfiguration.class)
            .addDependents(JettyWebXmlConfiguration.class)
            .hide("org.objectweb.asm."));
    }

    /**
     * Simple class to capture elapsed time of an operation.
     */
    public static class TimeStatistic
    {
        public long _start = 0;
        public long _end = 0;

        public void start()
        {
            _start = NanoTime.now();
        }

        public void end()
        {
            _end = NanoTime.now();
        }

        public long getElapsedNanos()
        {
            return NanoTime.elapsed(_start, _end);
        }
    }

    /**
     * ParserTask
     * <p>
     * Task to executing scanning of a resource for annotations.
     */
    public static class ParserTask implements Callable<Void>
    {
        protected final AnnotationParser _parser;
        protected final Set<? extends AnnotationParser.Handler> _handlers;
        protected final Resource _resource;
        protected TimeStatistic _stat;

        public ParserTask(AnnotationParser parser, Set<? extends AnnotationParser.Handler> handlers, Resource resource)
        {
            _parser = parser;
            _handlers = handlers;
            _resource = resource;
        }

        public void setStatistic(TimeStatistic stat)
        {
            _stat = stat;
        }

        @Override
        public Void call() throws Exception
        {
            if (_stat != null)
                _stat.start();
            if (_parser != null)
                _parser.parse(_handlers, _resource);
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
     * ServletContainerInitializerOrdering
     * <p>Applies an ordering to the {@link ServletContainerInitializer}s for the context, using
     * the value of the "org.eclipse.jetty.containerInitializerOrder" context attribute.
     * The attribute value is a list of classnames of ServletContainerInitializers in the order in which
     * they are to be called. One name only in the list can be "*", which is a
     * wildcard which matches any other ServletContainerInitializer name not already
     * matched.</p>
     */
    public static class ServletContainerInitializerOrdering
    {
        private final Map<String, Integer> _indexMap = new HashMap<>();
        private Integer _star = null;
        private String _ordering = null;

        public ServletContainerInitializerOrdering(String ordering)
        {
            if (ordering != null)
            {
                _ordering = ordering;

                String[] tmp = StringUtil.csvSplit(ordering);

                for (int i = 0; i < tmp.length; i++)
                {
                    String s = tmp[i].trim();
                    _indexMap.put(s, i);
                    if ("*".equals(s))
                    {
                        if (_star != null)
                            throw new IllegalArgumentException("Duplicate wildcards in ServletContainerInitializer ordering " + ordering);
                        _star = i;
                    }
                }
            }
        }

        /**
         * @return true if "*" is one of the values.
         */
        public boolean hasWildcard()
        {
            return _star != null;
        }

        /**
         * @return the index of the "*" element, if it is specified. -1 otherwise.
         */
        public int getWildcardIndex()
        {
            if (!hasWildcard())
                return -1;
            return _star;
        }

        /**
         * @return true if the ordering contains a single value of "*"
         */
        public boolean isDefaultOrder()
        {
            return (getSize() == 1 && hasWildcard());
        }

        /**
         * Get the order index of the given classname
         *
         * @param name the classname to look up
         * @return the index of the class name (or -1 if not found)
         */
        public int getIndexOf(String name)
        {
            Integer i = _indexMap.get(name);
            if (i == null)
                return -1;
            return i;
        }

        /**
         * Get the number of elements of the ordering
         *
         * @return the size of the index
         */
        public int getSize()
        {
            return _indexMap.size();
        }

        @Override
        public String toString()
        {
            if (_ordering == null)
                return "";
            return _ordering;
        }
    }

    /**
     * ServletContainerInitializerComparator
     * <p>
     * Comparator impl that orders a set of ServletContainerInitializers according to the
     * list of classnames (optionally containing a "*" wildcard character) established in a
     * ServletContainerInitializerOrdering.
     *
     * @see ServletContainerInitializerOrdering
     */
    public static class ServletContainerInitializerComparator implements Comparator<ServletContainerInitializer>
    {
        private final ServletContainerInitializerOrdering _ordering;

        public ServletContainerInitializerComparator(ServletContainerInitializerOrdering ordering)
        {
            _ordering = ordering;
        }

        @Override
        public int compare(ServletContainerInitializer sci1, ServletContainerInitializer sci2)
        {
            String c1 = (sci1 != null ? sci1.getClass().getName() : null);
            String c2 = (sci2 != null ? sci2.getClass().getName() : null);

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
    
    public static class DiscoveredServletContainerInitializerHolder extends ServletContainerInitializerHolder
    {
        private final Set<Class<?>> _handlesTypes = new HashSet<>();
        private final Set<String> _discoveredClassNames = new HashSet<>();
        
        public DiscoveredServletContainerInitializerHolder(Source source, ServletContainerInitializer sci, Class<?>... startupClasses)
        {
            super(source, sci);
            //take the classes and set them aside until we can calculate all of their
            //subclasses as necessary
            _handlesTypes.addAll(_startupClasses);
        }
        
        /**
         * Classes that have annotations that are listed in @HandlesTypes
         * are discovered by the ContainerInitializerAnnotationHandler
         * and added here.
         * @param names of classnames that have an annotation that is listed as a class in HandlesTypes
         */
        @Override
        public void addStartupClasses(String... names)
        {
            _discoveredClassNames.addAll(Arrays.asList(names));
        }

        /**
         * Classes that are listed in @HandlesTypes and found by
         * the createServletContainerInitializerAnnotationHandlers method.
         * @param clazzes classes listed in HandlesTypes
         */
        @Override
        public void addStartupClasses(Class<?>... clazzes)
        {
            _handlesTypes.addAll(Arrays.asList(clazzes));
        }

        @Override
        protected Set<Class<?>> resolveStartupClasses() throws Exception
        {
            final Set<Class<?>> classes = new HashSet<>();
            WebAppClassLoader.runWithServerClassAccess(() ->
            {
                for (String name:_startupClassNames)
                {
                    classes.add(Loader.loadClass(name));
                }
                return null;
            });
            return classes;
        }

        /**
         * Process each of the classes that are not annotations from @HandlesTypes and
         * find all of the subclasses/implementations.
         * Also process all of the classes that were discovered to have an annotation
         * that was listed in @HandlesTypes, and find all of their subclasses/implementations
         * in order to generate a complete set of classnames that can be passed into the 
         * onStartup method.
         * 
         * @param classMap complete inheritance tree of all classes in the webapp, can be
         * null if @HandlesTypes did not specify any classes.
         */
        void resolveClasses(Map<String, Set<String>> classMap)
        {
            Set<String> finalClassnames = new HashSet<>();

            if (classMap != null)
            {
                for (Class<?> c : _handlesTypes)
                {
                    //find all subclasses/implementations of the classes (not annotations) named in @HandlesTypes
                    if (!c.isAnnotation())
                        addInheritedTypes(finalClassnames, classMap, classMap.get(c.getName()));
                }

                for (String classname:_discoveredClassNames)
                {
                    //add each of the classes that were discovered to have an annotation listed in @HandlesTypes
                    finalClassnames.add(classname);
                    //walk its hierarchy and find all types that extend or implement the class
                    addInheritedTypes(finalClassnames, classMap, classMap.get(classname));
                }
            }
            
            //finally, add the complete set of startup classnames
            super.addStartupClasses(finalClassnames.toArray(new String[0]));
        }

        /**
         * Recursively walk the class hierarchy for the given set of classnames.
         * 
         * @param results all classes related to the set of classnames in names
         * @param classMap full inheritance tree for all classes in the webapp
         * @param names the names of classes for which to walk the hierarchy
         */
        private void addInheritedTypes(Set<String> results, Map<String, Set<String>> classMap, Set<String> names)
        {
            if (names == null || names.isEmpty())
                return;

            for (String s : names)
            {
                results.add(s);

                //walk the hierarchy and find all types that extend or implement the class
                addInheritedTypes(results, classMap, classMap.get(s));
            }
        }
    }

    @Override
    public void preConfigure(final WebAppContext context)
    {
        String tmp = (String)context.getAttribute(SERVLET_CONTAINER_INITIALIZER_EXCLUSION_PATTERN);
        State state = new State(context);
        context.setAttribute(STATE, state);
        state._sciExcludePattern = (tmp == null ? null : Pattern.compile(tmp));
    }

    private State getState(WebAppContext context)
    {
        if (context.getAttribute(STATE) instanceof State state)
            return state;
        throw new IllegalStateException("No state");
    }
    
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        State state = getState(context);

        //handle introspectable annotations (postconstruct,predestroy, multipart etc etc)
        context.getObjectFactory().addDecorator(new AnnotationDecorator(context));

        if (!context.getMetaData().isMetaDataComplete())
        {
            //If web.xml not metadata-complete, if this is a servlet 3 webapp or above
            //or configDiscovered is true, we need to search for annotations
            if (context.getServletContext().getEffectiveMajorVersion() >= 3 || context.isConfigurationDiscovered())
            {
                state._discoverableAnnotationHandlers.add(new WebServletAnnotationHandler(context));
                state._discoverableAnnotationHandlers.add(new WebFilterAnnotationHandler(context));
                state._discoverableAnnotationHandlers.add(new WebListenerAnnotationHandler(context));
            }
        }

        //Regardless of metadata, if there are any ServletContainerInitializers with @HandlesTypes, then we need to scan all the
        //classes so we can call their onStartup() methods correctly
        createServletContainerInitializerAnnotationHandlers(context, getNonExcludedInitializers(state));

        if (!state._discoverableAnnotationHandlers.isEmpty() || state._classInheritanceHandler != null || !state._containerInitializerAnnotationHandlers.isEmpty())
            scanForAnnotations(context, state);
        
        Map<String, Set<String>> map = (Map<String, Set<String>>)context.getAttribute(AnnotationConfiguration.CLASS_INHERITANCE_MAP);
        for (DiscoveredServletContainerInitializerHolder holder: state._sciHolders)
        {
            holder.resolveClasses(map);
            context.addServletContainerInitializer(holder); //only add the holder now all classes are fully available
        }
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        Map<String, Set<String>> classMap = (ClassInheritanceMap)context.getAttribute(CLASS_INHERITANCE_MAP);
        if (classMap != null)
            classMap.clear();
        context.removeAttribute(CLASS_INHERITANCE_MAP);

        if (!(context.removeAttribute(STATE) instanceof State state))
            throw new IllegalStateException("No state");
        state._discoverableAnnotationHandlers.clear();
        state._classInheritanceHandler = null;
        state._containerInitializerAnnotationHandlers.clear();
        state._sciHolders.clear();

        if (state._parserTasks != null)
        {
            state._parserTasks.clear();
            state._parserTasks = null;
        }

        super.postConfigure(context);
    }

    /**
     * Perform scanning of classes for discoverable
     * annotations such as WebServlet/WebFilter/WebListener
     *
     * @param context the context for the scan
     * @throws Exception if unable to scan
     */
    protected void scanForAnnotations(WebAppContext context, State state)
        throws Exception
    {
        int javaPlatform = 0;
        Object target = context.getAttribute(JavaVersion.JAVA_TARGET_PLATFORM);
        if (target != null)
            javaPlatform = Integer.parseInt(target.toString());
        AnnotationParser parser = createAnnotationParser(javaPlatform);
        state._parserTasks = new ArrayList<>();

        if (LOG.isDebugEnabled())
            LOG.debug("Annotation scanning commencing: webxml={}, metadatacomplete={}, configurationDiscovered={}, multiThreaded={}, maxScanWait={}",
                context.getServletContext().getEffectiveMajorVersion(),
                context.getMetaData().isMetaDataComplete(),
                context.isConfigurationDiscovered(),
                isUseMultiThreading(context),
                getMaxScanWait(context));

        //scan selected jars on the container classpath first
        parseContainerPath(context, parser);
        //email from Rajiv Mordani jsrs 315 7 April 2010
        //    If there is a <others/> then the ordering should be
        //          WEB-INF/classes the order of the declared elements + others.
        //    In case there is no others then it is
        //          WEB-INF/classes + order of the elements.
        parseWebInfClasses(state, parser);
        //scan non-excluded, non medatadata-complete jars in web-inf lib
        parseWebInfLib(state, parser);

        long start = NanoTime.now();

        //execute scan, either effectively synchronously (1 thread only), or asynchronously (limited by number of processors available) 
        final Semaphore task_limit = (isUseMultiThreading(context) ? new Semaphore(ProcessorUtils.availableProcessors()) : new Semaphore(1));
        final CountDownLatch latch = new CountDownLatch(state._parserTasks.size());
        final ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();

        for (final ParserTask p : state._parserTasks)
        {
            task_limit.acquire();
            context.getServer().getThreadPool().execute(() ->
            {
                multiException.callAndCatch(p::call);
                task_limit.release();
                latch.countDown();
            });
        }

        boolean timeout = !latch.await(getMaxScanWait(context), TimeUnit.SECONDS);
        long elapsedMs = NanoTime.millisSince(start);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Annotation scanning elapsed time={}ms", elapsedMs);
            for (ParserTask p : state._parserTasks)
            {
                LOG.debug("Scanned {} in {}ms", p.getResource(), TimeUnit.NANOSECONDS.toMillis(p.getStatistic().getElapsedNanos()));
            }

            LOG.debug("Scanned {} container path jars, {} WEB-INF/lib jars, {} WEB-INF/classes dirs in {}ms for context {}",
                (state._containerPathStats == null ? -1 : state._containerPathStats.getTotal()),
                (state._webInfLibStats == null ? -1 : state._webInfLibStats.getTotal()),
                (state._webInfClassesStats == null ? -1 : state._webInfClassesStats.getTotal()),
                elapsedMs,
                context);
        }

        if (timeout)
            multiException.add(new Exception("Timeout scanning annotations"));
        multiException.ifExceptionThrow();
    }

    /**
     * @param javaPlatform The java platform to scan for.
     * @return a new AnnotationParser. This method can be overridden to use a different implementation of
     * the AnnotationParser. Note that this is considered internal API.
     */
    protected AnnotationParser createAnnotationParser(int javaPlatform)
    {
        return new AnnotationParser(javaPlatform);
    }

    /**
     * Check if we should use multiple threads to scan for annotations or not
     *
     * @param context the context of the multi threaded setting
     * @return true if multi threading is enabled on the context, server, or via a System property.
     * @see #MULTI_THREADED
     */
    protected boolean isUseMultiThreading(WebAppContext context)
    {
        //try context attribute to see if we should use multithreading
        Object o = context.getAttribute(MULTI_THREADED);
        if (o instanceof Boolean)
        {
            return (Boolean)o;
        }
        //try server attribute to see if we should use multithreading
        o = context.getServer().getAttribute(MULTI_THREADED);
        if (o instanceof Boolean)
        {
            return (Boolean)o;
        }
        //try system property to see if we should use multithreading
        return Boolean.parseBoolean(System.getProperty(MULTI_THREADED, Boolean.toString(DEFAULT_MULTI_THREADED)));
    }

    /**
     * Work out how long we should wait for the async scanning to occur.
     *
     * @param context the context of the max scan wait setting
     * @return the max scan wait setting on the context, or server, or via a System property.
     * @see #MAX_SCAN_WAIT
     */
    protected int getMaxScanWait(WebAppContext context)
    {
        //try context attribute to get max time in sec to wait for scan completion
        Object o = context.getAttribute(MAX_SCAN_WAIT);
        if (o instanceof Number)
        {
            return ((Number)o).intValue();
        }
        //try server attribute to get max time in sec to wait for scan completion
        o = context.getServer().getAttribute(MAX_SCAN_WAIT);
        if (o instanceof Number)
        {
            return ((Number)o).intValue();
        }
        //try system property to get max time in sec to wait for scan completion
        return Integer.getInteger(MAX_SCAN_WAIT, DEFAULT_MAX_SCAN_WAIT);
    }

    public void createServletContainerInitializerAnnotationHandlers(WebAppContext context, List<ServletContainerInitializer> scis)
        throws Exception
    {
        if (scis == null || scis.isEmpty())
            return; // nothing to do

        State state = getState(context);

        for (ServletContainerInitializer sci : scis)
        {
            Class<?>[] classes = new Class<?>[0];
            HandlesTypes annotation = sci.getClass().getAnnotation(HandlesTypes.class);
            if (annotation != null)
                classes = annotation.value();
            
            DiscoveredServletContainerInitializerHolder holder = new DiscoveredServletContainerInitializerHolder(new Source(Origin.ANNOTATION, sci.getClass()), sci);
            state._sciHolders.add(holder);
            
            if (classes.length > 0)
            {   
                if (LOG.isDebugEnabled())
                    LOG.debug("HandlesTypes {} on initializer {}", Arrays.asList(classes), sci.getClass());
                
                //If we haven't already done so, we need to register a handler that will
                //process the whole class hierarchy to satisfy the ServletContainerInitializer
                if (context.getAttribute(CLASS_INHERITANCE_MAP) == null)
                {
                    Map<String, Set<String>> map = new ClassInheritanceMap();
                    context.setAttribute(CLASS_INHERITANCE_MAP, map);
                    state._classInheritanceHandler = new ClassInheritanceHandler(map);
                }
                
                for (Class<?> c : classes)
                {   
                    //The value of one of the HandlesTypes classes is actually an Annotation itself so
                    //register a handler for it to discover all classes that contain this annotation
                    if (c.isAnnotation())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Registering annotation handler for {}", c.getName());
                        state._containerInitializerAnnotationHandlers.add(new ContainerInitializerAnnotationHandler(holder, c));
                    }

                    holder.addStartupClasses(c);
                }
            }
        }
    }

    protected Resource getJarFor(WebAppContext context, ServletContainerInitializer service)
    {
        URI uri = TypeUtil.getLocationOfClass(service.getClass());
        if (uri == null)
            return null;
        return ResourceFactory.of(context).newResource(uri);
    }

    /**
     * Check to see if the ServletContainerIntializer loaded via the ServiceLoader came
     * from a jar that is excluded by the fragment ordering. See ServletSpec 3.0 p.85.
     *
     * @param context the context for the jars
     * @param sci the servlet container initializer
     * @param sciResource the resource for the servlet container initializer
     * @return true if excluded
     */
    public boolean isFromExcludedJar(WebAppContext context, ServletContainerInitializer sci, Resource sciResource)
    {
        if (sci == null)
            throw new IllegalArgumentException("ServletContainerInitializer null");
        if (context == null)
            throw new IllegalArgumentException("WebAppContext null");

        //if we don't know where its from it can't be excluded
        if (sciResource == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!Excluded {} null resource", sci);
            return false;
        }

        //A ServletContainerInitialier that came from WEB-INF/classes or equivalent cannot be excluded by an ordering
        if (isFromWebInfClasses(context, sciResource))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!Excluded {} from web-inf/classes", sci);
            return false;
        }

        //A ServletContainerInitializer that came from the container's classpath cannot be excluded by an ordering
        //of WEB-INF/lib jars
        if (isFromContainerClassPath(context, sci))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!Excluded {} from container classpath", sci);
            return false;
        }

        //If no ordering, nothing is excluded
        if (!context.getMetaData().isOrdered())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("!Excluded {} no ordering", sci);
            return false;
        }

        List<Resource> orderedJars = context.getMetaData().getWebInfResources(true);

        //there is an ordering, but there are no jars resulting from the ordering, everything excluded
        if (orderedJars.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Excluded {} empty ordering", sci);
            return true;
        }

        //Check if it is excluded by an ordering
        boolean included = false;
        for (Resource r : orderedJars)
        {
            included = r.equals(sciResource);
            if (included)
                break;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{}Excluded {} found={}", included ? "!" : "", sci, included);
        return !included;
    }

    /**
     * Test if the ServletContainerIntializer is excluded by the
     * o.e.j.containerInitializerExclusionPattern
     *
     * @param state the state of the Annotation parsing
     * @param sci the ServletContainerIntializer
     * @return true if the ServletContainerIntializer is excluded
     */
    protected boolean matchesExclusionPattern(State state, ServletContainerInitializer sci)
    {
        //no exclusion pattern, no SCI is excluded by it
        if (state._sciExcludePattern == null)
            return false;

        //test if name of class matches the regex
        if (LOG.isDebugEnabled())
            LOG.debug("Checking {} against containerInitializerExclusionPattern", sci.getClass().getName());
        return state._sciExcludePattern.matcher(sci.getClass().getName()).matches();
    }

    /**
     * Test if the ServletContainerInitializer is from the container classpath
     *
     * @param context the context for the webapp classpath
     * @param sci the ServletContainerIntializer
     * @return true if ServletContainerIntializer is from container classpath
     */
    public boolean isFromContainerClassPath(WebAppContext context, ServletContainerInitializer sci)
    {
        if (sci == null)
            return false;

        ClassLoader sciLoader = sci.getClass().getClassLoader();

        //if loaded by bootstrap loader, then its the container classpath
        if (sciLoader == null)
            return true;

        //if there is no context classloader, then its the container classpath
        if (context.getClassLoader() == null)
            return true;

        ClassLoader loader = sciLoader;
        while (loader != null)
        {
            if (loader == context.getClassLoader())
                return false; //the webapp classloader is in the ancestry of the classloader for the sci
            else
                loader = loader.getParent();
        }

        return true;
    }

    /**
     * Test if the ServletContainerInitializer is from WEB-INF/classes
     *
     * @param context the webapp to test
     * @param sci a Resource representing the SCI
     * @return true if the sci Resource is inside a WEB-INF/classes directory, false otherwise
     */
    public boolean isFromWebInfClasses(WebAppContext context, Resource sci)
    {
        for (Resource dir : context.getMetaData().getWebInfClassesResources())
        {
            if (dir.equals(sci))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Get SCIs that are not excluded from consideration
     *
     * @param state the web app annotation parse state
     * @return the list of non-excluded servlet container initializers
     */
    protected List<ServletContainerInitializer> getNonExcludedInitializers(State state)
    {
        WebAppContext context = state._context;
        ArrayList<ServletContainerInitializer> nonExcludedInitializers = new ArrayList<>();

        //We use the ServiceLoader mechanism to find the ServletContainerInitializer classes to inspect
        long start = NanoTime.now();
        List<ServletContainerInitializer> scis = TypeUtil.serviceProviderStream(ServiceLoader.load(ServletContainerInitializer.class)).flatMap(provider ->
        {
            try
            {
                return Stream.of(provider.get());
            }
            catch (Error e)
            {
                // Probably a SCI discovered on the system classpath that is hidden by the context classloader
                if (LOG.isDebugEnabled())
                    LOG.debug("Error: {} for {}", e.getMessage(), state._context, e);
                else
                    LOG.info("Error: {} for {}", e.getMessage(), state._context);
                return Stream.of();
            }
        }).toList();

        if (LOG.isDebugEnabled())
            LOG.debug("Service loaders found in {}ms", NanoTime.millisSince(start));

        Map<ServletContainerInitializer, Resource> sciResourceMap = new HashMap<>();
        ServletContainerInitializerOrdering initializerOrdering = getInitializerOrdering(context);

        //Get initial set of SCIs that aren't from excluded jars or excluded by the containerExclusionPattern, or excluded
        //because containerInitializerOrdering omits it
        for (ServletContainerInitializer sci : scis)
        {
            if (matchesExclusionPattern(state, sci))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} excluded by pattern", sci);
                continue;
            }

            Resource sciResource = getJarFor(context, sci);
            if (isFromExcludedJar(context, sci, sciResource))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} is from excluded jar", sci);
                continue;
            }

            //check containerInitializerOrdering doesn't exclude it
            String name = sci.getClass().getName();
            if (initializerOrdering != null && (!initializerOrdering.hasWildcard() && initializerOrdering.getIndexOf(name) < 0))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} is excluded by ordering", sci);
                continue;
            }

            sciResourceMap.put(sci, sciResource);
        }

        //Order the SCIs that are included
        if (initializerOrdering != null && !initializerOrdering.isDefaultOrder())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ordering ServletContainerInitializers with {}", initializerOrdering);

            //There is an ordering that is not just "*".
            //Arrange ServletContainerInitializers according to the ordering of classnames given, irrespective of coming from container or webapp classpaths
            nonExcludedInitializers.addAll(sciResourceMap.keySet());
            nonExcludedInitializers.sort(new ServletContainerInitializerComparator(initializerOrdering));
        }
        else
        {
            //No jetty-specific ordering specified, or just the wildcard value "*" specified.
            //Fallback to ordering the ServletContainerInitializers according to:
            //container classpath first, WEB-INF/classes then WEB-INF/lib (obeying any web.xml jar ordering)

            //First add in all SCIs that can't be excluded
            int lastContainerSCI = -1;
            for (Map.Entry<ServletContainerInitializer, Resource> entry : sciResourceMap.entrySet())
            {
                if (entry.getKey().getClass().getClassLoader() == context.getClassLoader().getParent())
                {
                    nonExcludedInitializers.add(++lastContainerSCI, entry.getKey()); //add all container SCIs before any webapp SCIs
                }
                else if (entry.getValue() == null) //can't work out provenance of SCI, so can't be ordered/excluded
                {
                    nonExcludedInitializers.add(entry.getKey()); //add at end of list
                }
                else
                {
                    for (Resource dir : context.getMetaData().getWebInfClassesResources())
                    {
                        if (dir.equals(entry.getValue()))//from WEB-INF/classes so can't be ordered/excluded
                        {
                            nonExcludedInitializers.add(entry.getKey());
                        }
                    }
                }
            }

            //throw out the ones we've already accounted for
            for (ServletContainerInitializer s : nonExcludedInitializers)
            {
                sciResourceMap.remove(s);
            }

            if (context.getMetaData().getOrdering() == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No web.xml ordering, ServletContainerInitializers in random order");
                //add the rest of the scis
                nonExcludedInitializers.addAll(sciResourceMap.keySet());
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Ordering ServletContainerInitializers with ordering {}", context.getMetaData().getOrdering());

                //add SCIs according to the ordering of its containing jar
                for (Resource webInfJar : context.getMetaData().getWebInfResources(true))
                {
                    for (Map.Entry<ServletContainerInitializer, Resource> entry : sciResourceMap.entrySet())
                    {
                        if (webInfJar.equals(entry.getValue()))
                            nonExcludedInitializers.add(entry.getKey());
                    }
                }
            }
        }

        //final pass over the non-excluded SCIs if the webapp version is < 3, in which case 
        //we will only call SCIs that are on the server's classpath
        if (context.getServletContext().getEffectiveMajorVersion() < 3 && !context.isConfigurationDiscovered())
        {
            ListIterator<ServletContainerInitializer> it = nonExcludedInitializers.listIterator();
            while (it.hasNext())
            {
                ServletContainerInitializer sci = it.next();
                if (!isFromContainerClassPath(context, sci))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Ignoring SCI {}: old web.xml version {}.{}", sci.getClass().getName(),
                            context.getServletContext().getEffectiveMajorVersion(),
                            context.getServletContext().getEffectiveMinorVersion());
                    it.remove();
                }
            }
        }

        if (LOG.isDebugEnabled())
        {
            int i = 0;
            for (ServletContainerInitializer sci : nonExcludedInitializers)
            {
                LOG.debug("ServletContainerInitializer: {} {} from {}", (++i), sci.getClass().getName(), sciResourceMap.get(sci));
            }
        }

        return nonExcludedInitializers;
    }

    /**
     * Jetty-specific extension that allows an ordering to be applied across ALL ServletContainerInitializers.
     *
     * @param context the context for the initializer ordering configuration
     * @return the ordering of the ServletContainerIntializer's
     */
    public ServletContainerInitializerOrdering getInitializerOrdering(WebAppContext context)
    {
        if (context == null)
            return null;

        String tmp = (String)context.getAttribute(SERVLET_CONTAINER_INITIALIZER_ORDER);
        if (StringUtil.isBlank(tmp))
            return null;

        return new ServletContainerInitializerOrdering(tmp);
    }

    /**
     * Scan jars on container path.
     *
     * @param context the context for the scan
     * @param parser the parser to scan with
     */
    public void parseContainerPath(final WebAppContext context, final AnnotationParser parser)
    {
        State state = getState(context);
        //always parse for discoverable annotations as well as class hierarchy and servletcontainerinitializer related annotations
        final Set<AnnotationParser.Handler> handlers = new HashSet<>();
        handlers.addAll(state._discoverableAnnotationHandlers);
        handlers.addAll(state._containerInitializerAnnotationHandlers);
        if (state._classInheritanceHandler != null)
            handlers.add(state._classInheritanceHandler);

        if (LOG.isDebugEnabled())
            state._containerPathStats = new CounterStatistic();

        //scan the container classpath jars that were selected by
        //filtering in MetaInfConfiguration
        for (Resource r : context.getMetaData().getContainerResources())
        {
            if (state._parserTasks != null)
            {
                ParserTask task = new ParserTask(parser, handlers, r);
                state._parserTasks.add(task);
                if (LOG.isDebugEnabled())
                {
                    state._containerPathStats.increment();
                    task.setStatistic(new TimeStatistic());
                }
            }
        }
    }

    /**
     * Scan jars in WEB-INF/lib.
     * <p>
     * Only jars selected by MetaInfConfiguration, and that are not excluded
     * by an ordering will be considered.
     *
     * @param state the state for the scan
     * @param parser the annotation parser to use
     * @throws Exception if unable to scan and/or parse
     */
    protected void parseWebInfLib(State state, final AnnotationParser parser) throws Exception
    {
        //email from Rajiv Mordani jsrs 315 7 April 2010
        //jars that do not have a web-fragment.xml are still considered fragments
        //they have to participate in the ordering

        //if there is an ordering, the ordered jars should be used.
        //If there is no ordering, jars will be unordered.
        WebAppContext context = state._context;
        List<Resource> jars = context.getMetaData().getWebInfResources(context.getMetaData().isOrdered());

        if (LOG.isDebugEnabled())
        {
            if (state._webInfLibStats == null)
                state._webInfLibStats = new CounterStatistic();
        }

        for (Resource r : jars)
        {
            //for each jar, we decide which set of annotations we need to parse for
            final Set<AnnotationParser.Handler> handlers = new HashSet<>();

            FragmentDescriptor f = context.getMetaData().getFragmentDescriptorForJar(r);

            //if its from a fragment jar that is metadata complete, we should skip scanning for @webservlet etc
            // but yet we still need to do the scanning for the classes on behalf of  the servletcontainerinitializers
            //if a jar has no web-fragment.xml we scan it (because it is not excluded by the ordering)
            //or if it has a fragment we scan it if it is not metadata complete
            if (!WebDescriptor.isMetaDataComplete(f) || state._classInheritanceHandler != null || !state._containerInitializerAnnotationHandlers.isEmpty())
            {
                //register the classinheritance handler if there is one
                if (state._classInheritanceHandler != null)
                    handlers.add(state._classInheritanceHandler);

                //register the handlers for the @HandlesTypes values that are themselves annotations if there are any
                handlers.addAll(state._containerInitializerAnnotationHandlers);

                //only register the discoverable annotation handlers if this fragment is not metadata complete, or has no fragment descriptor
                if (!WebDescriptor.isMetaDataComplete(f))
                    handlers.addAll(state._discoverableAnnotationHandlers);

                if (state._parserTasks != null)
                {
                    ParserTask task = new ParserTask(parser, handlers, r);
                    state._parserTasks.add(task);
                    if (LOG.isDebugEnabled())
                    {
                        state._webInfLibStats.increment();
                        task.setStatistic(new TimeStatistic());
                    }
                }
            }
        }
    }

    /**
     * Scan classes in WEB-INF/classes.
     *
     * @param state the state for the scan
     * @param parser the annotation parser to use
     */
    protected void parseWebInfClasses(State state, AnnotationParser parser)
    {
        WebAppContext context = state._context;
        Set<AnnotationParser.Handler> handlers = new HashSet<>(state._discoverableAnnotationHandlers);
        if (state._classInheritanceHandler != null)
            handlers.add(state._classInheritanceHandler);
        handlers.addAll(state._containerInitializerAnnotationHandlers);

        if (LOG.isDebugEnabled())
            state._webInfClassesStats = new CounterStatistic();

        for (Resource dir : context.getMetaData().getWebInfClassesResources())
        {
            if (state._parserTasks != null)
            {
                ParserTask task = new ParserTask(parser, handlers, dir);
                state._parserTasks.add(task);
                if (LOG.isDebugEnabled())
                {
                    state._webInfClassesStats.increment();
                    task.setStatistic(new TimeStatistic());
                }
            }
        }
    }

    public static class ClassInheritanceMap extends ConcurrentHashMap<String, Set<String>>
    {
        @Override
        public String toString()
        {
            return String.format("ClassInheritanceMap@%x{size=%d}", hashCode(), size());
        }
    }
}
