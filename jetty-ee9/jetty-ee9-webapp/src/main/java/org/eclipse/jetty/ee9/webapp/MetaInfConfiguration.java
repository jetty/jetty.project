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

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UriPatternPredicate;
import org.eclipse.jetty.util.resource.MountedPathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollators;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.ResourceUriPatternPredicate;
import org.eclipse.jetty.util.resource.Resources;
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
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection.
     */
    public static final String RESOURCE_DIRS = "org.eclipse.jetty.resources";

    public MetaInfConfiguration()
    {
        addDependencies(WebXmlConfiguration.class);
    }

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
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
     * To find them, examine the ModuleLayers hierarchy and the
     * ClassLoaders hierarchy above the webapp classloader that are
     * URLClassLoaders, and the system property java.class.path.
     *
     * @param context the WebAppContext being deployed
     */
    public void findAndFilterContainerPaths(final WebAppContext context) throws Exception
    {
        String pattern = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        if (LOG.isDebugEnabled())
            LOG.debug("{}={}", CONTAINER_JAR_PATTERN, pattern);
        if (StringUtil.isBlank(pattern))
            return; // TODO review if this short cut will allow later code simplifications

        ResourceFactory resourceFactory = context.getResourceFactory();

        // Apply an initial name filter to the jars to select which will be eventually
        // scanned for META-INF info and annotations. The filter is based on inclusion patterns.
        UriPatternPredicate uriPatternPredicate = new UriPatternPredicate(pattern, false);
        Consumer<Resource> addContainerResource = (resource) ->
        {
            if (Resources.missing(resource))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Classpath URI doesn't exist: " + resource);
            }
            else
            {
                context.getMetaData().addContainerResource(resource);
            }
        };

        List<URI> containerUris = getAllContainerJars(context);
        if (LOG.isDebugEnabled())
            LOG.debug("All container urls {}", containerUris);
        containerUris.stream()
            .filter(uriPatternPredicate)
            .map(resourceFactory::newResource)
            .filter(Objects::nonNull)
            .forEach(addContainerResource);

        // When running on jvm 9 or above, we won't be able to look at the application
        // classloader to extract urls, so we need to examine the classpath instead.
        String classPath = System.getProperty("java.class.path");
        if (classPath != null)
        {
            resourceFactory.split(classPath, File.pathSeparator)
                .stream()
                .filter(Objects::nonNull)
                .filter(r -> uriPatternPredicate.test(URIUtil.unwrapContainer(r.getURI())))
                .forEach(addContainerResource);
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
        String pattern = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        ResourceUriPatternPredicate webinfPredicate = new ResourceUriPatternPredicate(pattern, true);

        List<Resource> jars = findJars(context);
        if (LOG.isDebugEnabled())
            LOG.debug("webapp {}={} jars {}", WEBINF_JAR_PATTERN, pattern, jars);

        // Only add matching Resources to metadata.webInfResources
        if (jars != null)
        {
            jars.stream()
                .filter(webinfPredicate)
                .forEach(resource -> context.getMetaData().addWebInfResource(resource));
        }
    }

    protected List<URI> getAllContainerJars(final WebAppContext context)
    {
        Set<URI> locations = new HashSet<>();
        Module module = MetaInfConfiguration.class.getModule();
        // If the module is named, the JVM is running in JPMS mode.
        if (module.isNamed())
        {
            Deque<ModuleLayer> layers = new ArrayDeque<>();
            layers.push(module.getLayer());
            while (!layers.isEmpty())
            {
                ModuleLayer layer = layers.pop();
                // Process all the parent layers.
                layers.addAll(layer.parents());
                // Collect all the locations of the current configuration.
                layer.configuration().modules().stream()
                    .map(m -> m.reference().location())
                    .map(optional -> optional.orElse(null))
                    .filter(Objects::nonNull)
                    // Skip the JDK modules.
                    .filter(uri -> !uri.getScheme().equalsIgnoreCase("jrt"))
                    .collect(Collectors.toCollection(() -> locations));            }
        }

        ClassLoader loader = MetaInfConfiguration.class.getClassLoader();
        while (loader != null)
        {
            if (loader instanceof URLClassLoader urlCL)
                URIUtil.streamOf(urlCL).forEach(locations::add);
            loader = loader.getParent();
        }

        return List.copyOf(locations);
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
            context.setBaseResource(ResourceFactory.combine(collection));
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
    @SuppressWarnings("unchecked")
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
            try (ResourceFactory.Closeable scanResourceFactory = ResourceFactory.closeable())
            {
                for (Resource dir : jars)
                {
                    try
                    {
                        //if not already a directory, convert it by mounting as jar file
                        if (!dir.isDirectory())
                            dir = scanResourceFactory.newJarFileResource(dir.getURI());
                    }
                    catch (Exception e)
                    {
                        //not an appropriate uri, skip it
                        continue;
                    }

                    if (isEmptyResource(dir))
                        continue;

                    if (scanTypes.contains(METAINF_RESOURCES))
                        scanForResources(context, dir, metaInfResourceCache);
                    if (scanTypes.contains(METAINF_FRAGMENTS))
                        scanForFragment(context, dir, metaInfFragmentCache);
                    if (scanTypes.contains(METAINF_TLDS))
                        scanForTlds(context, dir, metaInfTldCache);
                }
            }
        }
    }

    /**
     * Scan for META-INF/resources dir in the given directory.
     *
     * @param context the context for the scan
     * @param dir the target directory to scan
     * @param cache the resource cache
     */
    public void scanForResources(WebAppContext context, Resource dir, ConcurrentHashMap<Resource, Resource> cache)
    {
        // Resource target does not exist
        if (isEmptyResource(dir))
            return;

        Resource resourcesDir = null;

        if (cache != null && cache.containsKey(dir))
        {
            resourcesDir = cache.get(dir);
            if (isEmptyResource(resourcesDir))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} cached as containing no META-INF/resources", dir);
                return;
            }
            else if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/resources found in cache ", dir);
        }
        else
        {
            //not using caches or not in the cache so check for the resources dir
            if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/resources checked", dir);

            resourcesDir = dir.resolve("/META-INF/resources");

            if (isEmptyResource(resourcesDir))
                return;

            //convert from an ephemeral Resource to one that is associated with the context's lifecycle
            resourcesDir = context.getResourceFactory().newResource(resourcesDir.getURI());

            if (cache != null)
            {
                Resource old = cache.putIfAbsent(dir, resourcesDir);
                if (old != null)
                    resourcesDir = old;
                else if (LOG.isDebugEnabled())
                    LOG.debug("{} META-INF/resources cache updated", dir);
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
        return resourcesDir == null || !resourcesDir.isDirectory();
    }

    /**
     * Scan for META-INF/web-fragment.xml file in the given jar.
     *
     * @param context the context for the scan
     * @param dir the directory to scan for fragments
     * @param cache the resource cache
     */
    public void scanForFragment(WebAppContext context, Resource dir, ConcurrentHashMap<Resource, Resource> cache)
    {
        Resource webFrag = null;
        if (cache != null && cache.containsKey(dir))
        {
            webFrag = cache.get(dir);
            if (isEmptyFragment(webFrag))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} cached as containing no META-INF/web-fragment.xml", dir);
                return;
            }
            else if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/web-fragment.xml found in cache ", dir);
        }
        else
        {
            //not using caches or not in the cache so check for the web-fragment.xml
            if (LOG.isDebugEnabled())
                LOG.debug("{} META-INF/web-fragment.xml checked", dir);

            webFrag = dir.resolve("META-INF/web-fragment.xml");

            if (isEmptyFragment(webFrag))
                return;

            //convert ephemeral Resource to one associated with the context's lifecycle ResourceFactory
            webFrag = context.getResourceFactory().newResource(webFrag.getURI());

            if (cache != null)
            {
                Resource old = cache.putIfAbsent(dir, webFrag);
                if (old != null)
                    webFrag = old;
                else if (LOG.isDebugEnabled())
                    LOG.debug("{} META-INF/web-fragment.xml cache updated", dir);
            }
        }

        Map<Resource, Resource> fragments = (Map<Resource, Resource>)context.getAttribute(METAINF_FRAGMENTS);
        if (fragments == null)
        {
            fragments = new HashMap<Resource, Resource>();
            context.setAttribute(METAINF_FRAGMENTS, fragments);
        }

        if (dir instanceof MountedPathResource)
        {
            //ensure we link from the original .jar rather than jar:file:
            dir = context.getResourceFactory().newResource(((MountedPathResource)dir).getContainerPath());
        }

        fragments.put(dir, webFrag);
        if (LOG.isDebugEnabled())
            LOG.debug("{} added to context", webFrag);
    }

    private static boolean isEmptyFragment(Resource webFrag)
    {
        return !Resources.isReadableFile(webFrag);
    }

    /**
     * Discover META-INF/*.tld files in the given jar
     *
     * @param context the context for the scan
     * @param dir the directory to scan for .tlds
     * @param cache the resource cache
     * @throws Exception if unable to scan for .tlds
     */
    public void scanForTlds(WebAppContext context, Resource dir, ConcurrentHashMap<Resource, Collection<URL>> cache)
        throws Exception
    {
        Collection<URL> tlds;

        if (isEmptyResource(dir))
            return;

        if (cache != null && cache.containsKey(dir))
        {
            Collection<URL> tmp = cache.get(dir);
            if (tmp.isEmpty())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} cached as containing no tlds", dir);
                return;
            }
            else
            {
                tlds = tmp;
                if (LOG.isDebugEnabled())
                    LOG.debug("{} tlds found in cache ", dir);
            }
        }
        else
        {
            //not using caches or not in the cache so find all tlds
            tlds = new HashSet<>();
            tlds.addAll(getTlds(context, dir));

            if (cache != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} tld cache updated", dir);
                Collection<URL> old = cache.putIfAbsent(dir, tlds);
                if (old != null)
                    tlds = old;
            }

            if (tlds.isEmpty())
                return;
        }

        Collection<URL> metaInfTlds = (Collection<URL>)context.getAttribute(METAINF_TLDS);
        if (metaInfTlds == null)
        {
            metaInfTlds = new HashSet<>();
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
     * Find all .tld files in the given jar.
     *
     * @param dir the dir to check for .tlds
     * @return the collection of tlds as url references
     * @throws IOException if unable to scan the jar file
     */
    private Collection<URL> getTlds(WebAppContext context, Resource dir) throws IOException
    {
        HashSet<URL> tlds = new HashSet<>();

        Resource metaInf = dir.resolve("META-INF");
        if (isEmptyResource(metaInf))
            return tlds; //no tlds

        try (Stream<Path> stream = Files.walk(metaInf.getPath()))
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
        classDirs.addAll(findExtraClasspathDirs(context));

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
        List<Resource> jarResources = new ArrayList<>();
        jarResources.addAll(findWebInfLibJars(context));
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
        if (context == null)
            return List.of();

        Resource webInf = context.getWebInf();
        if (Resources.isReadableDirectory(webInf))
        {
            Resource webInfLib = webInf.resolve("lib");

            if (Resources.isReadableDirectory(webInfLib))
            {
                return webInfLib.list().stream()
                    .filter((lib) -> FileID.isLibArchive(lib.getFileName()))
                    .sorted(ResourceCollators.byName(true))
                    .collect(Collectors.toList());
            }
        }

        return List.of();
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
        if (Resources.isReadableDirectory(webInf))
        {
            // Look for classes directory
            Resource classesDir = webInf.resolve("classes/");
            if (Resources.isReadableDirectory(classesDir))
                return classesDir;
        }
        return null;
    }

    /**
     * Get class dirs from WebAppContext.getExtraClasspath as resources
     *
     * @param context the context to look for extra classpaths in
     * @return the list of Resources to the extra classpath
     */
    protected List<Resource> findExtraClasspathDirs(WebAppContext context)
    {
        if (context == null || context.getExtraClasspath() == null)
            return List.of();

        return context.getExtraClasspath()
            .stream()
            .filter(Resource::isDirectory)
            .collect(Collectors.toList());
    }

    private boolean isFileSupported(Resource resource)
    {
        return FileID.isLibArchive(resource.getURI());
    }
}
