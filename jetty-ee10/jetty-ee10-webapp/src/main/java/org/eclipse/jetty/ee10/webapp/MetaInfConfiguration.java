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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.NullMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UriPatternPredicate;
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

    /**
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection.
     */
    public static final String RESOURCE_DIRS = "org.eclipse.jetty.resources";

    private static final Map<Resource, Resource> NOOP_RESOURCE_CACHE = new NullMap<>();
    private static final Map<Resource, Collection<URL>> NOOP_TLD_CACHE = new NullMap<>();

    public MetaInfConfiguration()
    {
        super(new Builder().addDependencies(WebXmlConfiguration.class));
    }

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        // pre-emptively create empty lists for tlds, fragments and resources as context attributes
        // this signals that this class has been called. This differentiates the case where this class
        // has been called but finds no META-INF data from the case where this class was never called
        //noinspection UrlHashCode
        Set<URL> metaInfTlds = (HashSet<URL>)context.getAttribute(METAINF_TLDS); // TODO: make this not a Set to avoid URL.hashcode issues
        if (metaInfTlds == null)
            metaInfTlds = new HashSet<>();
        context.setAttribute(METAINF_TLDS, metaInfTlds);

        Set<Resource> metaInfResources = (HashSet<Resource>)context.getAttribute(METAINF_RESOURCES);
        if (metaInfResources == null)
            metaInfResources = new HashSet<>();
        context.setAttribute(METAINF_RESOURCES, metaInfResources);

        Map<Resource, Resource> metaInfFragments = (HashMap<Resource, Resource>)context.getAttribute(METAINF_FRAGMENTS);
        if (metaInfFragments == null)
            metaInfFragments = new HashMap<>();
        context.setAttribute(METAINF_FRAGMENTS, metaInfFragments);

        // No pattern to apply for WEB-INF/classes, so just add to metadata
        context.getMetaData().setWebInfClassesResources(findClassesDirs(context));

        // Collect container paths that have selection patterns
        // add them to the context metadata
        List<Resource> containerResources = getContainerPaths(context);
        containerResources.stream()
            .forEach(r -> context.getMetaData().addContainerResource(r));

        // Collect webapp paths that have selection patterns
        // add them to the context metadata
        List<Resource> webappResources = getWebAppPaths(context);
        webappResources.stream()
            .forEach(r -> context.getMetaData().addWebInfResource(r));

        // -- Scan of META-INF directories --

        // Figure out container caching rules
        boolean useContainerCache = getUseContainerCache(context);
        Server server = context.getServer();
        Map<Resource, Resource> metaInfResourceCache = getResourceCache(server, useContainerCache);
        Map<Resource, Resource> metaInfFragmentCache = getFragmentCache(server, useContainerCache);
        Map<Resource, Collection<URL>> metaInfTldCache = getTldCache(server, useContainerCache);

        // Restrict scan to resource targets that have a META-INF directory
        List<Resource> containerTargets = containerResources.stream()
            .filter(r -> Resources.isReadableDirectory(r.resolve("META-INF")))
            .toList();

        List<Resource> webappTargets = webappResources.stream()
            .filter(r -> Resources.isReadableDirectory(r.resolve("META-INF")))
            .toList();

        // Scan for META-INF/*.tld entries
        metaInfTlds.addAll(scanTlds(streamTargets(containerTargets, webappTargets, true), metaInfTldCache));

        boolean scanWebAppTargets = needsServlet3FeatureScan(context);

        // Scan for META-INF/resources/ entries
        metaInfResources.addAll(scanMetaInfResources(streamTargets(containerTargets, webappTargets, scanWebAppTargets), metaInfResourceCache));

        // Scan for META-INF/web-fragment.xml entries
        metaInfFragments.putAll(scanMetaInfFragments(streamTargets(containerTargets, webappTargets, scanWebAppTargets), metaInfFragmentCache));
    }

    private Stream<Resource> streamTargets(Collection<Resource> containerTargets, Collection<Resource> webappTargets, boolean scanWebAppResources)
    {
        if (scanWebAppResources)
            return Stream.concat(containerTargets.stream(), webappTargets.stream());
        else
            return containerTargets.stream();
    }

    private Collection<URL> scanTlds(Stream<Resource> targets, Map<Resource, Collection<URL>> cache)
    {
        assert cache != null;

        return targets
            .flatMap(target -> getTlds(target, cache).stream())
            // Using toSet to return unique hits
            .collect(Collectors.toSet()); // TODO: consider returning Set<URI> instead to avoid URL.hashcode issues
    }

    private Collection<URL> getTlds(Resource target, Map<Resource, Collection<URL>> cache)
    {
        Collection<URL> tlds = cache.get(target);
        if (tlds != null)
            return tlds;

        Resource metaInfDir = target.resolve("META-INF");
        if (!Resources.isReadableDirectory(metaInfDir))
            return List.of();

        List<URL> urls = metaInfDir.list()
            .stream()
            .filter(Resources::isReadableFile)
            .filter(r -> FileID.isExtension(r.getFileName(), "tld"))
            .map(Resource::getURI)
            .map(URIUtil::toURL)
            .toList();
        cache.putIfAbsent(target, urls);

        return urls;
    }

    private Map<Resource, Resource> scanMetaInfFragments(Stream<Resource> targets, Map<Resource, Resource> cache)
    {
        assert cache != null;

        return targets
            .map(target -> getMetaInfFragment(target, cache))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(FragmentMapping::target, FragmentMapping::fragment));
    }

    private record FragmentMapping(Resource target, Resource fragment) {}

    private FragmentMapping getMetaInfFragment(Resource target, Map<Resource, Resource> cache)
    {
        Resource fragment = cache.get(target);
        if (Resources.isReadableFile(fragment))
            return new FragmentMapping(target, fragment);

        fragment = target.resolve("META-INF/web-fragment.xml");
        if (Resources.isReadableFile(fragment))
        {
            cache.putIfAbsent(target, fragment);
            return new FragmentMapping(target, fragment);
        }

        return null;
    }

    private List<Resource> scanMetaInfResources(Stream<Resource> targets, Map<Resource, Resource> cache)
    {
        assert cache != null;

        return targets
            .map(target -> getMetaInfResourceDir(target, cache))
            .filter(Objects::nonNull)
            .toList();
    }

    private Resource getMetaInfResourceDir(Resource target, Map<Resource, Resource> cache)
    {
        Resource dir = cache.get(target);
        if (Resources.isReadableDirectory(dir))
            return dir;

        dir = target.resolve("META-INF/resources");
        if (Resources.isReadableDirectory(dir))
        {
            cache.putIfAbsent(target, dir);
            return dir;
        }
        return null;
    }

    /**
     * Only look for Servlet 3+ features ({@code META-INF/web-fragment.xml} and {@code META-INF/resources})
     * if web.xml is not metadata complete, or it declares version 3.0 or greater
     *
     * @param context the context to evaluate
     * @return true if servlet 3+ features should be scanned for
     */
    // TODO: is this behavior still needed for ee10? to support servlet behaviors pre 3.0 we would need to load javax.servlet classes, right? (seems to only be important for ee8 env)
    private boolean needsServlet3FeatureScan(WebAppContext context)
    {
        if (context == null)
            return false;

        if (context.getMetaData().isMetaDataComplete())
            return false;

        if (context.getServletContext().getEffectiveMajorVersion() < 3 && !context.isConfigurationDiscovered())
            return false;

        return true;
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        // Look for extra resource
        Set<Resource> resources = (Set<Resource>)context.getAttribute(RESOURCE_DIRS);
        if (resources != null && !resources.isEmpty())
        {
            List<Resource> collection = new ArrayList<>();
            collection.add(context.getBaseResource());
            collection.addAll(resources);
            context.setBaseResource(ResourceFactory.combine(collection));
        }
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(METAINF_RESOURCES, null);
        context.setAttribute(METAINF_FRAGMENTS, null);
        context.setAttribute(METAINF_TLDS, null);
    }

    /**
     * Get the list of Container Paths that should be scanned for META-INF configuration.
     *
     * @param context the context to search from
     * @return the List of Resource objects to search for META-INF information in (order determined by implementation).
     * @throws Exception if unable to get the Container Paths.
     */
    protected List<Resource> getContainerPaths(WebAppContext context)
        throws Exception
    {
        String pattern = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        if (LOG.isDebugEnabled())
            LOG.debug("{}={}", CONTAINER_JAR_PATTERN, pattern);
        if (StringUtil.isBlank(pattern))
            return List.of();

        // Apply an initial name filter to the jars to select which will be eventually
        // scanned for META-INF info and annotations. The filter is based on inclusion patterns.
        UriPatternPredicate containerUriPredicate = new UriPatternPredicate(pattern, false);

        // We collect the unique URIs for the container first
        // as the same URI can exist in multiple places
        Set<URI> uniqueURIs = new HashSet<>();
        uniqueURIs.addAll(getContainerClassLoaderEntries(context));
        uniqueURIs.addAll(getJavaClassPathEntries());
        uniqueURIs.addAll(getJdkModulePathEntries());

        // Stream the selected paths, based on the container include pattern
        return uniqueURIs.stream()
            .filter(containerUriPredicate)
            .map(uri -> newDirectoryResource(context, uri))
            .sorted(ResourceCollators.byName(true))
            .toList();
    }

    protected boolean getUseContainerCache(WebAppContext context)
    {
        boolean useContainerCache = DEFAULT_USE_CONTAINER_METAINF_CACHE;
        if (context != null && context.getServer() != null)
        {
            Boolean attr = (Boolean)context.getServer().getAttribute(USE_CONTAINER_METAINF_CACHE);
            if (attr != null)
                useContainerCache = attr;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} = {}", USE_CONTAINER_METAINF_CACHE, useContainerCache);
        return useContainerCache;
    }

    /**
     * The list of Container ClassLoader entries.
     *
     * @param context the context to search from
     * @return the List of URIs to in the classloader entries.
     */
    protected List<URI> getContainerClassLoaderEntries(WebAppContext context)
    {
        ClassLoader loader = MetaInfConfiguration.class.getClassLoader();
        Set<URI> uris = new HashSet<>();
        while (loader != null)
        {
            if (loader instanceof URLClassLoader urlCL)
            {
                URIUtil.streamOf(urlCL).forEach(uris::add);
            }
            loader = loader.getParent();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Found {} container classloader entries: {}", uris.size(), uris.stream().map(Objects::toString).sorted().collect(Collectors.joining(", ", "[", "]")));
        return uris.stream().sorted().toList();
    }

    /**
     * Get the List of {@code java.class.path} (System property) entries.
     *
     * @return the List of URIs in the {@code java.class.path} value.
     */
    protected List<URI> getJavaClassPathEntries()
    {
        // On some JVMs we won't be able to look at the application
        // classloader to extract urls, so we need to examine the classpath instead.
        String classPath = System.getProperty("java.class.path");
        if (StringUtil.isBlank(classPath))
            return List.of();

        Set<URI> uris = Stream.of(classPath.split(File.pathSeparator))
            .map(URIUtil::toURI)
            .collect(Collectors.toSet());
        if (LOG.isDebugEnabled())
            LOG.debug("Found {} java.class.path jars: {}", uris.size(), uris.stream().map(Objects::toString).sorted().collect(Collectors.joining(", ", "[", "]")));
        return uris.stream().sorted().toList();
    }

    protected List<URI> getJdkModulePathEntries()
    {
        // We also need to examine the other module path properties
        // TODO need to consider the jdk.module.upgrade.path - how to resolve which modules will be actually used.
        String modulePath = System.getProperty("jdk.module.path");
        if (StringUtil.isBlank(modulePath))
            return List.of();

        Set<URI> uris = Stream.of(modulePath.split(File.pathSeparator))
            .map(URIUtil::toURI)
            .collect(Collectors.toSet());
        if (LOG.isDebugEnabled())
            LOG.debug("Found {} jdk.module.path jars: {}", uris.size(), uris.stream().map(Objects::toString).sorted().collect(Collectors.joining(", ", "[", "]")));
        return uris.stream().sorted().toList();
    }

    /**
     * Get the list of WebApp Paths that should be scanned for META-INF configuration.
     *
     * @param context the context to search from
     * @return the List of Resource objects to search for META-INF information in (order determined by implementation).
     * @throws Exception if unable to get the WebApp Paths.
     */
    protected List<Resource> getWebAppPaths(WebAppContext context)
        throws Exception
    {
        // Apply filter to WEB-INF/lib jars
        String pattern = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        ResourceUriPatternPredicate webinfPredicate = new ResourceUriPatternPredicate(pattern, true);

        List<Resource> uniquePaths = new ArrayList<>();
        uniquePaths.addAll(getWebInfLibJars(context));
        uniquePaths.addAll(getExtraClassPathEntries(context));

        if (LOG.isDebugEnabled())
            LOG.debug("WebApp Paths: {}", uniquePaths.stream().map(Resource::getURI).map(URI::toASCIIString).collect(Collectors.joining(", ", "[", "]")));

        return uniquePaths;
    }

    /**
     * Get the List of Resources that need to be scanned in the context's {@code WEB-INF/lib/} tree.
     *
     * @param context the context to search from
     * @return the List of Resources that need to be scanned.
     */
    protected List<Resource> getWebInfLibJars(WebAppContext context)
    {
        // Selection filter to apply to discovered WEB-INF/lib jars
        String pattern = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        ResourceUriPatternPredicate webinfPredicate = new ResourceUriPatternPredicate(pattern, true);

        if (context == null)
            return List.of();

        Resource webInf = context.getWebInf();
        if (!Resources.isReadableDirectory(webInf))
            return List.of();

        Resource webInfLib = webInf.resolve("lib");
        if (!Resources.isReadableDirectory(webInfLib))
            return List.of();

        List<Resource> jars = webInfLib.list().stream()
            .filter((lib) -> FileID.isLibArchive(lib.getFileName()))
            .map(r -> toDirectoryResource(context, r))
            .filter(webinfPredicate)
            .sorted(ResourceCollators.byName(true))
            .toList();

        if (LOG.isDebugEnabled())
            LOG.debug("WEB-INF/lib Jars ({}={}) : {}", WEBINF_JAR_PATTERN, pattern, jars.stream().map(Resource::getURI).map(URI::toASCIIString).collect(Collectors.joining(", ", "[", "]")));

        return jars;
    }

    /**
     * Get the List of Resources that have been specified in the {@link WebAppContext#setExtraClasspath(List)} style methods.
     *
     * @param context the context to get configuration from
     * @return the List of extra classpath entries
     */
    protected List<Resource> getExtraClassPathEntries(WebAppContext context)
    {
        if (context == null || context.getExtraClasspath() == null)
            return List.of();

        List<Resource> jars = context.getExtraClasspath()
            .stream()
            .filter(r -> FileID.isLibArchive(r.getURI()))
            .map(r -> toDirectoryResource(context, r))
            .toList();

        if (LOG.isDebugEnabled())
            LOG.debug("Extra-Classpath Jars {}", jars.stream().map(Resource::getURI).map(URI::toASCIIString).collect(Collectors.joining(", ", "[", "]")));

        return jars;
    }

    private Map<Resource, Collection<URL>> getTldCache(Server server, boolean useCaches)
    {
        if (!useCaches || server == null)
            return NOOP_TLD_CACHE;

        Map<Resource, Collection<URL>> cache = (Map<Resource, Collection<URL>>)server.getAttribute(CACHED_CONTAINER_TLDS);
        if (cache == null)
        {
            cache = new ConcurrentHashMap<>();
            server.setAttribute(CACHED_CONTAINER_TLDS, cache);
        }
        return cache;
    }

    private Map<Resource, Resource> getFragmentCache(Server server, boolean useCaches)
    {
        if (!useCaches || server == null)
            return NOOP_RESOURCE_CACHE;

        Map<Resource, Resource> cache = (Map<Resource, Resource>)server.getAttribute(CACHED_CONTAINER_FRAGMENTS);
        if (cache == null)
        {
            cache = new ConcurrentHashMap<>();
            server.setAttribute(CACHED_CONTAINER_FRAGMENTS, cache);
        }
        return cache;
    }

    private Map<Resource, Resource> getResourceCache(Server server, boolean useCaches)
    {
        if (!useCaches || server == null)
            return NOOP_RESOURCE_CACHE;

        Map<Resource, Resource> cache = (ConcurrentHashMap<Resource, Resource>)server.getAttribute(CACHED_CONTAINER_RESOURCES);
        if (cache == null)
        {
            cache = new ConcurrentHashMap<>();
            server.setAttribute(CACHED_CONTAINER_RESOURCES, cache);
        }
        return cache;
    }

    protected List<Resource> findClassesDirs(WebAppContext context)
        throws Exception
    {
        if (context == null)
            return null;

        List<Resource> classDirs = new ArrayList<>();

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
        List<Resource> jarResources = new ArrayList<>(findWebInfLibJars(context));
        List<Resource> extraClasspathJars = findExtraClasspathJars(context);
        if (extraClasspathJars != null)
            jarResources.addAll(extraClasspathJars);
        return jarResources;
    }

    /**
     * Look for jars in {@code WEB-INF/lib}
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
        if (webInf == null || !webInf.exists() || !webInf.isDirectory())
            return List.of();

        Resource webInfLib = webInf.resolve("lib");

        if (Resources.isReadableDirectory(webInfLib))
        {
            return webInfLib.list().stream()
                .filter((lib) -> FileID.isLibArchive(lib.getFileName()))
                .map(r -> toDirectoryResource(context, r))
                .sorted(ResourceCollators.byName(true))
                .collect(Collectors.toList());
        }
        else
        {
            return List.of();
        }
    }

    /**
     * Get jars from WebAppContext.getExtraClasspath as resources
     *
     * @param context the context to find extra classpath jars in
     * @return the list of Resources with the extra classpath, or null if not found
     */
    protected List<Resource> findExtraClasspathJars(WebAppContext context)
    {
        if (context == null || context.getExtraClasspath() == null)
            return null;

        return context.getExtraClasspath()
            .stream()
            .filter(r -> FileID.isLibArchive(r.getURI()))
            .map(r -> toDirectoryResource(context, r))
            .collect(Collectors.toList());
    }

    /**
     * Get {@code WEB-INF/classes} dir
     *
     * @param context the context to look for the {@code WEB-INF/classes} directory
     * @return the Resource for the {@code WEB-INF/classes} directory
     * @throws Exception if unable to find the {@code WEB-INF/classes} directory
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
            Resource webInfClassesDir = webInf.resolve("classes/");
            if (Resources.isReadableDirectory(webInfClassesDir))
                return webInfClassesDir;
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
            .map(path -> toDirectoryResource(context, path))
            .collect(Collectors.toList());
    }

    private Resource newDirectoryResource(WebAppContext context, Path path)
    {
        if (path == null)
            return null;
        return newDirectoryResource(context, path.toUri());
    }

    private Resource newDirectoryResource(WebAppContext context, URI uri)
    {
        if (FileID.isJavaArchive(uri) &&
            !"jar".equals(uri.getScheme()))
        {
            return context.getResourceFactory().newJarFileResource(uri);
        }

        return context.getResourceFactory().newResource(uri);
    }

    private Resource toDirectoryResource(WebAppContext context, Resource resource)
    {
        if (Resources.isReadable(resource) &&
            FileID.isJavaArchive(resource.getURI()) &&
            !"jar".equals(resource.getURI().getScheme()))
        {
            return context.getResourceFactory().newJarFileResource(resource.getURI());
        }
        return resource;
    }

    private boolean isFileSupported(Resource resource)
    {
        return FileID.isLibArchive(resource.getURI());
    }
}
