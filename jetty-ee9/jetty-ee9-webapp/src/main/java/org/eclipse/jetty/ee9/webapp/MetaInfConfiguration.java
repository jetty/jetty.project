//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.PatternMatcher;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MetaInfConfiguration
 * <p>
 *
 * Scan META-INF of jars to find:
 * <ul>
 * <li>tlds</li>
 * <li>web-fragment.xml</li>
 * <li>resources</li>
 * </ul>
 *
 * The jars which are scanned are:
 * <ol>
 * <li>those from the container classpath whose pattern matched the WebInfConfiguration.CONTAINER_JAR_PATTERN</li>
 * <li>those from WEB-INF/lib</li>
 * </ol>
 */
public class MetaInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(MetaInfConfiguration.class);

    public static final String USE_CONTAINER_METAINF_CACHE = "org.eclipse.jetty.metainf.useCache";
    public static final boolean DEFAULT_USE_CONTAINER_METAINF_CACHE = true;
    public static final String CACHED_CONTAINER_TLDS = "org.eclipse.jetty.tlds.cache";
    public static final String CACHED_CONTAINER_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES + ".cache";
    public static final String CACHED_CONTAINER_RESOURCES = "org.eclipse.jetty.resources.cache";
    public static final String METAINF_TLDS = "org.eclipse.jetty.tlds";
    public static final String METAINF_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES;
    public static final String METAINF_RESOURCES = "org.eclipse.jetty.resources";
    public static final String CONTAINER_JAR_PATTERN = "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern";
    public static final String WEBINF_JAR_PATTERN = "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern";
    public static final List<String> __allScanTypes = Arrays.asList(METAINF_TLDS, METAINF_RESOURCES, METAINF_FRAGMENTS);

    /**
     * ContainerPathNameMatcher
     *
     * Matches names of jars on the container classpath
     * against a pattern. If no pattern is specified, no
     * jars match.
     */
    public class ContainerPathNameMatcher extends PatternMatcher
    {
        protected final WebAppContext _context;
        protected final String _pattern;

        public ContainerPathNameMatcher(WebAppContext context, String pattern)
        {
            if (context == null)
                throw new IllegalArgumentException("Context null");
            _context = context;
            _pattern = pattern;
        }

        public void match(List<URI> uris) throws Exception
        {
            if (uris == null)
                return;
            match(_pattern, uris.toArray(new URI[uris.size()]), false);
        }

        @Override
        public void matched(URI uri)
        {
            _context.getMetaData().addContainerResource(_resourceFactory.newResource(uri));
        }
    }

    /**
     * WebAppPathNameMatcher
     *
     * Matches names of jars or dirs on the webapp classpath
     * against a pattern. If there is no pattern, all jars or dirs
     * will match.
     */
    public class WebAppPathNameMatcher extends PatternMatcher
    {
        protected final WebAppContext _context;
        protected final String _pattern;

        public WebAppPathNameMatcher(WebAppContext context, String pattern)
        {
            if (context == null)
                throw new IllegalArgumentException("Context null");
            _context = context;
            _pattern = pattern;
        }

        public void match(List<URI> uris)
            throws Exception
        {
            match(_pattern, uris.toArray(new URI[uris.size()]), true);
        }

        @Override
        public void matched(URI uri)
        {
            _context.getMetaData().addWebInfResource(_resourceFactory.newResource(uri));
        }
    }

    /**
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection.
     */
    public static final String RESOURCE_DIRS = "org.eclipse.jetty.resources";

    private ResourceFactory.Closeable _resourceFactory;

    public MetaInfConfiguration()
    {
        addDependencies(WebXmlConfiguration.class);
    }

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        _resourceFactory = ResourceFactory.closeable();

        //find container jars/modules and select which ones to scan
        findAndFilterContainerPaths(context);

        //find web-app jars and select which ones to scan
        findAndFilterWebAppPaths(context);

        //No pattern to appy to classes, just add to metadata
        context.getMetaData().setWebInfClassesResources(findClassDirs(context));

        scanJars(context);
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        IO.close(_resourceFactory);
        _resourceFactory = null;
        super.deconfigure(context);
    }

    /**
     * Find jars and directories that are on the container's classpath
     * and apply an optional filter. The filter is a pattern applied to the
     * full jar or directory names. If there is no pattern, then no jar
     * or dir is considered to match.
     *
     * Those jars that do match will be later examined for META-INF
     * information and annotations.
     *
     * To find them, examine the classloaders in the hierarchy above the
     * webapp classloader that are URLClassLoaders. For jdk-9 we also
     * look at the java.class.path, and the jdk.module.path.
     *
     * @param context the WebAppContext being deployed
     */
    public void findAndFilterContainerPaths(final WebAppContext context) throws Exception
    {
        String pattern = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        if (StringUtil.isBlank(pattern))
            return; // TODO review if this short cut will allow later code simplifications

        // Apply an initial name filter to the jars to select which will be eventually
        // scanned for META-INF info and annotations. The filter is based on inclusion patterns.
        ContainerPathNameMatcher containerPathNameMatcher = new ContainerPathNameMatcher(context, pattern);
        List<URI> containerUris = getAllContainerJars(context);

        if (LOG.isDebugEnabled())
            LOG.debug("Matching container urls {}", containerUris);
        containerPathNameMatcher.match(containerUris);

        // When running on jvm 9 or above, we we won't be able to look at the application
        // classloader to extract urls, so we need to examine the classpath instead.
        String classPath = System.getProperty("java.class.path");
        if (classPath != null)
        {
            List<URI> cpUris = new ArrayList<>();
            String[] entries = classPath.split(File.pathSeparator);
            for (String entry : entries)
            {
                File f = new File(entry);
                cpUris.add(f.toURI());
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Matching java.class.path {}", cpUris);
            containerPathNameMatcher.match(cpUris);
        }

        // We also need to examine the module path.
        // TODO need to consider the jdk.module.upgrade.path - how to resolve
        // which modules will be actually used. If its possible, it can
        // only be attempted in jetty-10 with jdk-9 specific apis.
        String modulePath = System.getProperty("jdk.module.path");
        if (modulePath != null)
        {
            List<URI> moduleUris = new ArrayList<>();
            String[] entries = modulePath.split(File.pathSeparator);
            for (String entry : entries)
            {
                File file = new File(entry);
                if (file.isDirectory())
                {
                    File[] files = file.listFiles();
                    if (files != null)
                    {
                        for (File f : files)
                        {
                            moduleUris.add(f.toURI());
                        }
                    }
                }
                else
                {
                    moduleUris.add(file.toURI());
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Matching jdk.module.path {}", moduleUris);
            containerPathNameMatcher.match(moduleUris);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Container paths selected:{}", context.getMetaData().getContainerResources());
    }

    /**
     * Finds the jars that are either physically or virtually in
     * WEB-INF/lib, and applies an optional filter to their full
     * pathnames.
     *
     * The filter selects which jars will later be examined for META-INF
     * information and annotations. If there is no pattern, then
     * all jars are considered selected.
     *
     * @param context the WebAppContext being deployed
     */
    public void findAndFilterWebAppPaths(WebAppContext context)
        throws Exception
    {
        //Apply filter to WEB-INF/lib jars
        WebAppPathNameMatcher matcher = new WebAppPathNameMatcher(context, (String)context.getAttribute(WEBINF_JAR_PATTERN));

        List<Resource> jars = findJars(context);

        //Convert to uris for matching
        if (jars != null)
        {
            List<URI> uris = new ArrayList<>();
            for (Resource r : jars)
            {
                uris.add(r.getURI());
            }
            matcher.match(uris);
        }
    }

    protected List<URI> getAllContainerJars(final WebAppContext context)
    {
        ClassLoader loader = MetaInfConfiguration.class.getClassLoader();
        List<URI> uris = new ArrayList<>();
        while (loader != null)
        {
            if (loader instanceof URLClassLoader urlCL)
            {
                URIUtil.streamOf(urlCL).forEach(uris::add);
            }
            loader = loader.getParent();
        }
        return uris;
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        // Look for extra resource
        @SuppressWarnings("unchecked")
        Set<Resource> resources = (Set<Resource>)context.getAttribute(RESOURCE_DIRS);
        if (resources != null && !resources.isEmpty())
        {
            List<Resource> collection = new ArrayList<>();
            collection.add(context.getBaseResource());
            collection.addAll(resources);
            context.setBaseResource(Resource.combine(collection));
        }
    }

    protected void scanJars(WebAppContext context) throws Exception
    {
        boolean useContainerCache = DEFAULT_USE_CONTAINER_METAINF_CACHE;
        if (context.getServer() != null)
        {
            Boolean attr = (Boolean)context.getServer().getAttribute(USE_CONTAINER_METAINF_CACHE);
            if (attr != null)
                useContainerCache = attr.booleanValue();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} = {}", USE_CONTAINER_METAINF_CACHE, useContainerCache);

        //pre-emptively create empty lists for tlds, fragments and resources as context attributes
        //this signals that this class has been called. This differentiates the case where this class
        //has been called but finds no META-INF data from the case where this class was never called
        if (context.getAttribute(METAINF_TLDS) == null)
            context.setAttribute(METAINF_TLDS, new HashSet<URL>());
        if (context.getAttribute(METAINF_RESOURCES) == null)
            context.setAttribute(METAINF_RESOURCES, new HashSet<Resource>());
        if (context.getAttribute(METAINF_FRAGMENTS) == null)
            context.setAttribute(METAINF_FRAGMENTS, new HashMap<Resource, Resource>());

        //always scan everything from the container's classpath
        scanJars(context, context.getMetaData().getContainerResources(), useContainerCache, __allScanTypes);
        //only look for fragments if web.xml is not metadata complete, or it version 3.0 or greater
        List<String> scanTypes = new ArrayList<>(__allScanTypes);
        if (context.getMetaData().isMetaDataComplete() || (context.getServletContext().getEffectiveMajorVersion() < 3) && !context.isConfigurationDiscovered())
            scanTypes.remove(METAINF_FRAGMENTS);
        scanJars(context, context.getMetaData().getWebInfResources(false), false, scanTypes);
    }

    /**
     * For backwards compatibility. This method will always scan for all types of data.
     *
     * @param context the context for the scan
     * @param jars the jars to scan
     * @param useCaches if true, the scanned info is cached
     * @throws Exception if unable to scan the jars
     */
    public void scanJars(final WebAppContext context, Collection<Resource> jars, boolean useCaches)
        throws Exception
    {
        scanJars(context, jars, useCaches, __allScanTypes);
    }

    /**
     * Look into the jars to discover info in META-INF. If useCaches == true, then we will
     * cache the info discovered indexed by the jar in which it was discovered: this speeds
     * up subsequent context deployments.
     *
     * @param context the context for the scan
     * @param jars the jars resources to scan
     * @param useCaches if true, cache the info discovered
     * @param scanTypes the type of things to look for in the jars
     * @throws Exception if unable to scan the jars
     */
    public void scanJars(final WebAppContext context, Collection<Resource> jars, boolean useCaches, List<String> scanTypes)
        throws Exception
    {
        ConcurrentHashMap<Resource, Resource> metaInfResourceCache = null;
        ConcurrentHashMap<Resource, Resource> metaInfFragmentCache = null;
        ConcurrentHashMap<Resource, Collection<URL>> metaInfTldCache = null;
        if (useCaches)
        {
            metaInfResourceCache = (ConcurrentHashMap<Resource, Resource>)context.getServer().getAttribute(CACHED_CONTAINER_RESOURCES);
            if (metaInfResourceCache == null)
            {
                metaInfResourceCache = new ConcurrentHashMap<Resource, Resource>();
                context.getServer().setAttribute(CACHED_CONTAINER_RESOURCES, metaInfResourceCache);
            }
            metaInfFragmentCache = (ConcurrentHashMap<Resource, Resource>)context.getServer().getAttribute(CACHED_CONTAINER_FRAGMENTS);
            if (metaInfFragmentCache == null)
            {
                metaInfFragmentCache = new ConcurrentHashMap<Resource, Resource>();
                context.getServer().setAttribute(CACHED_CONTAINER_FRAGMENTS, metaInfFragmentCache);
            }
            metaInfTldCache = (ConcurrentHashMap<Resource, Collection<URL>>)context.getServer().getAttribute(CACHED_CONTAINER_TLDS);
            if (metaInfTldCache == null)
            {
                metaInfTldCache = new ConcurrentHashMap<Resource, Collection<URL>>();
                context.getServer().setAttribute(CACHED_CONTAINER_TLDS, metaInfTldCache);
            }
        }

        //Scan jars for META-INF information
        if (jars != null)
        {
            for (Resource r : jars)
            {
                if (scanTypes.contains(METAINF_RESOURCES))
                    scanForResources(context, r, metaInfResourceCache);
                if (scanTypes.contains(METAINF_FRAGMENTS))
                    scanForFragment(context, r, metaInfFragmentCache);
                if (scanTypes.contains(METAINF_TLDS))
                    scanForTlds(context, r, metaInfTldCache);
            }
        }
    }

    /**
     * Scan for META-INF/resources dir in the given jar.
     *
     * @param context the context for the scan
     * @param target the target resource to scan for
     * @param cache the resource cache
     * @throws Exception if unable to scan for resources
     */
    public void scanForResources(WebAppContext context, Resource target, ConcurrentHashMap<Resource, Resource> cache)
        throws Exception
    {
        Resource resourcesDir = null;
        if (cache != null && cache.containsKey(target))
        {
            resourcesDir = cache.get(target);
            if (isEmptyResource(resourcesDir))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} cached as containing no META-INF/resources", target);
                return;
            }
            else if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/resources found in cache ", target);
        }
        else
        {
            //not using caches or not in the cache so check for the resources dir
            if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/resources checked", target);
            if (target.isDirectory())
            {
                //TODO think  how to handle an unpacked jar file (eg for osgi)
                resourcesDir = target.resolve("/META-INF/resources");
            }
            else
            {
                //Resource represents a packed jar
                URI uri = target.getURI();
                resourcesDir = _resourceFactory.newResource(URIUtil.uriJarPrefix(uri, "!/META-INF/resources"));
            }

            if (cache != null)
            {
                Resource old = cache.putIfAbsent(target, resourcesDir);
                if (old != null)
                    resourcesDir = old;
                else if (LOG.isDebugEnabled())
                    LOG.debug("{} META-INF/resources cache updated", target);
            }

            if (isEmptyResource(resourcesDir))
            {
                return;
            }
        }

        //add it to the meta inf resources for this context
        Set<Resource> dirs = (Set<Resource>)context.getAttribute(METAINF_RESOURCES);
        if (dirs == null)
        {
            dirs = new HashSet<Resource>();
            context.setAttribute(METAINF_RESOURCES, dirs);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} added to context", resourcesDir);

        dirs.add(resourcesDir);
    }

    private static boolean isEmptyResource(Resource resourcesDir)
    {
        return !resourcesDir.exists() || !resourcesDir.isDirectory();
    }

    /**
     * Scan for META-INF/web-fragment.xml file in the given jar.
     *
     * @param context the context for the scan
     * @param jar the jar resource to scan for fragements in
     * @param cache the resource cache
     * @throws Exception if unable to scan for fragments
     */
    public void scanForFragment(WebAppContext context, Resource jar, ConcurrentHashMap<Resource, Resource> cache)
        throws Exception
    {
        Resource webFrag = null;
        if (cache != null && cache.containsKey(jar))
        {
            webFrag = cache.get(jar);
            if (isEmptyFragment(webFrag))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} cached as containing no META-INF/web-fragment.xml", jar);
                return;
            }
            else if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/web-fragment.xml found in cache ", jar);
        }
        else
        {
            //not using caches or not in the cache so check for the web-fragment.xml
            if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/web-fragment.xml checked", jar);
            if (jar.isDirectory())
            {
                webFrag = _resourceFactory.newResource(jar.getPath().resolve("META-INF/web-fragment.xml"));
            }
            else
            {
                URI uri = jar.getURI();
                webFrag = _resourceFactory.newResource(URIUtil.uriJarPrefix(uri, "!/META-INF/web-fragment.xml"));
            }

            if (cache != null)
            {
                //web-fragment.xml doesn't exist: put token in cache to signal we've seen the jar
                Resource old = cache.putIfAbsent(jar, webFrag);
                if (old != null)
                    webFrag = old;
                else if (LOG.isDebugEnabled())
                    LOG.debug("{} META-INF/web-fragment.xml cache updated", jar);
            }

            if (isEmptyFragment(webFrag))
                return;
        }

        Map<Resource, Resource> fragments = (Map<Resource, Resource>)context.getAttribute(METAINF_FRAGMENTS);
        if (fragments == null)
        {
            fragments = new HashMap<Resource, Resource>();
            context.setAttribute(METAINF_FRAGMENTS, fragments);
        }
        fragments.put(jar, webFrag);
        if (LOG.isDebugEnabled())
            LOG.debug("{} added to context", webFrag);
    }

    private static boolean isEmptyFragment(Resource webFrag)
    {
        return !webFrag.exists() || webFrag.isDirectory();
    }

    /**
     * Discover META-INF/*.tld files in the given jar
     *
     * @param context the context for the scan
     * @param jar the jar resources to scan tlds for
     * @param cache the resource cache
     * @throws Exception if unable to scan for tlds
     */
    public void scanForTlds(WebAppContext context, Resource jar, ConcurrentHashMap<Resource, Collection<URL>> cache)
        throws Exception
    {
        Collection<URL> tlds = null;

        if (cache != null && cache.containsKey(jar))
        {
            Collection<URL> tmp = cache.get(jar);
            if (tmp.isEmpty())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} cached as containing no tlds", jar);
                return;
            }
            else
            {
                tlds = tmp;
                if (LOG.isDebugEnabled())
                    LOG.debug("{} tlds found in cache ", jar);
            }
        }
        else
        {
            //not using caches or not in the cache so find all tlds
            tlds = new HashSet<URL>();
            if (jar.isDirectory())
            {
                tlds.addAll(getTlds(jar.getPath()));
            }
            else
            {
                URI uri = jar.getURI();
                tlds.addAll(getTlds(uri));
            }

            if (cache != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} tld cache updated", jar);
                Collection<URL> old = (Collection<URL>)cache.putIfAbsent(jar, tlds);
                if (old != null)
                    tlds = old;
            }

            if (tlds.isEmpty())
                return;
        }

        Collection<URL> metaInfTlds = (Collection<URL>)context.getAttribute(METAINF_TLDS);
        if (metaInfTlds == null)
        {
            metaInfTlds = new HashSet<URL>();
            context.setAttribute(METAINF_TLDS, metaInfTlds);
        }
        metaInfTlds.addAll(tlds);
        if (LOG.isDebugEnabled())
            LOG.debug("tlds added to context");
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(METAINF_RESOURCES, null);

        context.setAttribute(METAINF_FRAGMENTS, null);

        context.setAttribute(METAINF_TLDS, null);
    }

    /**
     * Find all .tld files in all subdirs of the given dir.
     *
     * @param dir the directory to scan
     * @return the list of tlds found
     * @throws IOException if unable to scan the directory
     */
    public Collection<URL> getTlds(Path dir) throws IOException
    {
        if (dir == null || !Files.isDirectory(dir))
            return Collections.emptySet();

        Set<URL> tlds = new HashSet<>();

        try (Stream<Path> entries = Files.walk(dir)
            .filter(Files::isRegularFile)
            .filter(FileID::isTld))
        {
            Iterator<Path> iter = entries.iterator();
            while (iter.hasNext())
            {
                Path entry = iter.next();
                tlds.add(entry.toUri().toURL());
            }
        }

        return tlds;
    }

    /**
     * Find all .tld files in the given jar.
     *
     * @param uri the uri to jar file
     * @return the collection of tlds as url references
     * @throws IOException if unable to scan the jar file
     */
    public Collection<URL> getTlds(URI uri) throws IOException
    {
        HashSet<URL> tlds = new HashSet<>();
        Resource r = _resourceFactory.newResource(URIUtil.uriJarPrefix(uri, "!/"));
        try (Stream<Path> stream = Files.walk(r.getPath()))
        {
            Iterator<Path> it = stream
                .filter(Files::isRegularFile)
                .filter(FileID::isTld)
                .iterator();
            while (it.hasNext())
            {
                Path entry = it.next();
                tlds.add(entry.toUri().toURL());
            }
        }
        return tlds;
    }

    protected List<Resource> findClassDirs(WebAppContext context)
        throws Exception
    {
        if (context == null)
            return null;

        List<Resource> classDirs = new ArrayList<Resource>();

        Resource webInfClasses = findWebInfClassesDir(context);
        if (webInfClasses != null)
            classDirs.add(webInfClasses);
        List<Resource> extraClassDirs = findExtraClasspathDirs(context);
        if (extraClassDirs != null)
            classDirs.addAll(extraClassDirs);

        return classDirs;
    }

    /**
     * Look for jars that should be treated as if they are in WEB-INF/lib
     *
     * @param context the context to find the jars in
     * @return the list of jar resources found within context
     * @throws Exception if unable to find the jars
     */
    protected List<Resource> findJars(WebAppContext context)
        throws Exception
    {
        List<Resource> jarResources = new ArrayList<Resource>();
        List<Resource> webInfLibJars = findWebInfLibJars(context);
        if (webInfLibJars != null)
            jarResources.addAll(webInfLibJars);
        List<Resource> extraClasspathJars = findExtraClasspathJars(context);
        if (extraClasspathJars != null)
            jarResources.addAll(extraClasspathJars);
        return jarResources;
    }

    /**
     * Look for jars in <code>WEB-INF/lib</code>
     *
     * @param context the context to find the lib jars in
     * @return the list of jars as {@link Resource}
     * @throws Exception if unable to scan for lib jars
     */
    protected List<Resource> findWebInfLibJars(WebAppContext context)
        throws Exception
    {
        Resource webInf = context.getWebInf();
        if (webInf == null || !webInf.exists())
            return null;

        Resource webInfLib = webInf.resolve("/lib");
        if (!webInfLib.exists() || !webInfLib.isDirectory())
        {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(webInf.getPath()))
        {
            return entries
                .filter(Files::isRegularFile)
                .filter(FileID::isArchive)
                .sorted(PathCollators.byName(true))
                // return the jar itself, not the contents
                .map((path) -> context.getResourceFactory().newResource(path))
                .toList();
        }
    }

    /**
     * Get jars from WebAppContext.getExtraClasspath as resources
     *
     * @param context the context to find extra classpath jars in
     * @return the list of Resources with the extra classpath, or null if not found
     * @throws Exception if unable to resolve the extra classpath jars
     */
    protected List<Resource> findExtraClasspathJars(WebAppContext context)
        throws Exception
    {
        if (context == null || context.getExtraClasspath() == null)
            return null;

        return context.getExtraClasspath()
            .getResources()
            .stream()
            .filter(this::isFileSupported)
            .collect(Collectors.toList());
    }

    /**
     * Get <code>WEB-INF/classes</code> dir
     *
     * @param context the context to look for the <code>WEB-INF/classes</code> directory
     * @return the Resource for the <code>WEB-INF/classes</code> directory
     * @throws Exception if unable to find the <code>WEB-INF/classes</code> directory
     */
    protected Resource findWebInfClassesDir(WebAppContext context)
        throws Exception
    {
        if (context == null)
            return null;

        Resource webInf = context.getWebInf();

        // Find WEB-INF/classes
        if (webInf != null && webInf.isDirectory())
        {
            // Look for classes directory
            Resource classes = webInf.resolve("classes/");
            if (classes.exists())
                return classes;
        }
        return null;
    }

    /**
     * Get class dirs from WebAppContext.getExtraClasspath as resources
     *
     * @param context the context to look for extra classpaths in
     * @return the list of Resources to the extra classpath
     * @throws Exception if unable to resolve the extra classpath resources
     */
    protected List<Resource> findExtraClasspathDirs(WebAppContext context)
        throws Exception
    {
        if (context == null || context.getExtraClasspath() == null)
            return null;

        return context.getExtraClasspath().getResources()
            .stream()
            .filter(Resource::isDirectory)
            .collect(Collectors.toList());
    }

    private boolean isFileSupported(Resource resource)
    {
        return FileID.isArchive(resource.getURI());
    }
}
