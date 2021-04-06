//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.PatternMatcher;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarFileResource;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

public class WebInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(WebInfConfiguration.class);

    public static final String TEMPDIR_CONFIGURED = "org.eclipse.jetty.tmpdirConfigured";
    public static final String CONTAINER_JAR_PATTERN = "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern";
    public static final String WEBINF_JAR_PATTERN = "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern";

    /**
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection.
     */
    public static final String RESOURCE_DIRS = "org.eclipse.jetty.resources";

    protected Resource _preUnpackBaseResource;

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
        protected final Pattern _pattern;

        public ContainerPathNameMatcher(WebAppContext context, Pattern pattern)
        {
            if (context == null)
                throw new IllegalArgumentException("Context null");
            _context = context;
            _pattern = pattern;
        }

        public void match(List<URI> uris)
            throws Exception
        {
            if (uris == null)
                return;
            match(_pattern, uris.toArray(new URI[uris.size()]), false);
        }

        @Override
        public void matched(URI uri) throws Exception
        {
            _context.getMetaData().addContainerResource(Resource.newResource(uri));
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
        protected final Pattern _pattern;

        public WebAppPathNameMatcher(WebAppContext context, Pattern pattern)
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
        public void matched(URI uri) throws Exception
        {
            _context.getMetaData().addWebInfJar(Resource.newResource(uri));
        }
    }

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        // Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);

        // Extract webapp if necessary
        unpack(context);

        findAndFilterContainerPaths(context);

        findAndFilterWebAppPaths(context);

        // No pattern to apply to classes, just add to metadata
        context.getMetaData().setWebInfClassesDirs(findClassDirs(context));
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
     * @throws Exception if unable to apply optional filtering on the container's classpath
     */
    public void findAndFilterContainerPaths(final WebAppContext context) throws Exception
    {
        //assume the target jvm is the same as that running
        int currentPlatform = JavaVersion.VERSION.getPlatform();
        //allow user to specify target jvm different to current runtime
        int targetPlatform = currentPlatform;
        Object target = context.getAttribute(JavaVersion.JAVA_TARGET_PLATFORM);
        if (target != null)
            targetPlatform = Integer.parseInt(target.toString());

        //Apply an initial name filter to the jars to select which will be eventually
        //scanned for META-INF info and annotations. The filter is based on inclusion patterns.
        String tmp = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        Pattern containerPattern = (tmp == null ? null : Pattern.compile(tmp));
        ContainerPathNameMatcher containerPathNameMatcher = new ContainerPathNameMatcher(context, containerPattern);

        ClassLoader loader = null;
        if (context.getClassLoader() != null)
            loader = context.getClassLoader().getParent();

        List<URI> containerUris = new ArrayList<>();

        while (loader instanceof URLClassLoader)
        {
            URL[] urls = ((URLClassLoader)loader).getURLs();
            if (urls != null)
            {
                for (URL u : urls)
                {
                    try
                    {
                        containerUris.add(u.toURI());
                    }
                    catch (URISyntaxException e)
                    {
                        containerUris.add(new URI(URIUtil.encodeSpaces(u.toString())));
                    }
                }
            }
            loader = loader.getParent();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Matching container urls {}", containerUris);
        containerPathNameMatcher.match(containerUris);

        //if running on jvm 9 or above, we we won't be able to look at the application classloader
        //to extract urls, so we need to examine the classpath instead.
        if (currentPlatform >= 9)
        {
            tmp = System.getProperty("java.class.path");
            if (tmp != null)
            {
                List<URI> cpUris = new ArrayList<>();
                String[] entries = tmp.split(File.pathSeparator);
                for (String entry : entries)
                {
                    File f = new File(entry);
                    cpUris.add(f.toURI());
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("Matching java.class.path {}", cpUris);
                containerPathNameMatcher.match(cpUris);
            }
        }

        //if we're targeting jdk 9 or above, we also need to examine the 
        //module path
        if (targetPlatform >= 9)
        {
            //TODO need to consider the jdk.module.upgrade.path - how to resolve
            //which modules will be actually used. If its possible, it can
            //only be attempted in jetty-10 with jdk-9 specific apis.
            tmp = System.getProperty("jdk.module.path");
            if (tmp != null)
            {
                List<URI> moduleUris = new ArrayList<>();
                String[] entries = tmp.split(File.pathSeparator);
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
     * @throws Exception if unable to find the jars or apply filtering
     */
    public void findAndFilterWebAppPaths(WebAppContext context)
        throws Exception
    {
        String tmp = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        Pattern webInfPattern = (tmp == null ? null : Pattern.compile(tmp));
        //Apply filter to WEB-INF/lib jars
        WebAppPathNameMatcher matcher = new WebAppPathNameMatcher(context, webInfPattern);

        List<Resource> jars = findJars(context);

        //Convert to uris for matching
        if (jars != null)
        {
            List<URI> uris = new ArrayList<>();
            int i = 0;
            for (Resource r : jars)
            {
                uris.add(r.getURI());
            }
            matcher.match(uris);
        }
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        //cannot configure if the context is already started
        if (context.isStarted())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Cannot configure webapp " + context + " after it is started");
            return;
        }

        Resource webInf = context.getWebInf();

        // Add WEB-INF classes and lib classpaths
        if (webInf != null && webInf.isDirectory() && context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes = webInf.addPath("classes/");
            if (classes.exists())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes);

            // Look for jars
            Resource lib = webInf.addPath("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
        }

        // Look for extra resource
        @SuppressWarnings("unchecked")
        Set<Resource> resources = (Set<Resource>)context.getAttribute(RESOURCE_DIRS);
        if (resources != null && !resources.isEmpty())
        {
            Resource[] collection = new Resource[resources.size() + 1];
            int i = 0;
            collection[i++] = context.getBaseResource();
            for (Resource resource : resources)
            {
                collection[i++] = resource;
            }
            context.setBaseResource(new ResourceCollection(collection));
        }
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        File tempDirectory = context.getTempDirectory();

        // if we're not persisting the temp dir contents delete it
        if (!context.isPersistTempDirectory())
        {
            IO.delete(tempDirectory);
        }

        //if it wasn't explicitly configured by the user, then unset it
        Boolean tmpdirConfigured = (Boolean)context.getAttribute(TEMPDIR_CONFIGURED);
        if (tmpdirConfigured != null && !tmpdirConfigured)
            context.setTempDirectory(null);

        //reset the base resource back to what it was before we did any unpacking of resources
        if (context.getBaseResource() != null)
            context.getBaseResource().close();
        context.setBaseResource(_preUnpackBaseResource);
    }

    /**
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#cloneConfigure(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
        Path tmpDir = Files.createTempDirectory(template.getTempDirectory().getParentFile().toPath(), WebInfConfiguration.getCanonicalNameForWebAppTmpDir(context));
        File tmpDirAsFile = tmpDir.toFile();
        tmpDirAsFile.deleteOnExit();
        context.setTempDirectory(tmpDirAsFile);
    }

    /**
     * Get a temporary directory in which to unpack the war etc etc.
     * The algorithm for determining this is to check these alternatives
     * in the order shown:
     * <p>
     * A. Try to use an explicit directory specifically for this webapp:
     * <ol>
     * <li>
     * Iff an explicit directory is set for this webapp, use it. Set delete on
     * exit depends on value of persistTempDirectory.
     * </li>
     * <li>
     * Iff javax.servlet.context.tempdir context attribute is set for
     * this webapp &amp;&amp; exists &amp;&amp; writeable, then use it. Set delete on exit depends on
     * value of persistTempDirectory.
     * </li>
     * </ol>
     *
     * <p>
     * B. Create a directory based on global settings. The new directory
     * will be called <code>"Jetty-"+host+"-"+port+"__"+context+"-"+virtualhost+"-"+randomdigits+".dir"</code>
     * <p>
     * If the user has specified the context attribute org.eclipse.jetty.webapp.basetempdir, the
     * directory specified by this attribute will be the parent of the temp dir created. Otherwise,
     * the parent dir is <code>${java.io.tmpdir}</code>. Set delete on exit depends on value of persistTempDirectory.
     *
     * @param context the context to resolve the temp directory from
     * @throws Exception if unable to resolve the temp directory
     */
    public void resolveTempDirectory(WebAppContext context)
        throws Exception
    {
        //If a tmp directory is already set we should use it
        File tmpDir = context.getTempDirectory();
        if (tmpDir != null)
        {
            configureTempDirectory(tmpDir, context);
            context.setAttribute(TEMPDIR_CONFIGURED, Boolean.TRUE); //the tmp dir was set explicitly
            return;
        }

        // No temp directory configured, try to establish one via the javax.servlet.context.tempdir.
        File servletTmpDir = asFile(context.getAttribute(WebAppContext.TEMPDIR));
        if (servletTmpDir != null)
        {
            // Use as tmpDir
            tmpDir = servletTmpDir;
            configureTempDirectory(tmpDir, context);
            // Ensure Attribute has File object
            context.setAttribute(WebAppContext.TEMPDIR, tmpDir);
            // Set as TempDir in context.
            context.setTempDirectory(tmpDir);
            return;
        }

        //We need to make a temp dir. Check if the user has set a directory to use instead
        //of java.io.tmpdir as the parent of the dir
        File baseTemp = asFile(context.getAttribute(WebAppContext.BASETEMPDIR));
        if (baseTemp != null)
        {
            if (!baseTemp.isDirectory() || !baseTemp.canWrite())
                throw new IllegalStateException("BASETEMPDIR is not a writable directory");

            //Make a temp directory as a child of the given base dir
            makeTempDirectory(baseTemp, context);
            return;
        }

        //Look for a directory named "work" in ${jetty.base} and
        //treat it as parent of a new temp dir (which we will persist)
        File jettyBase = asFile(System.getProperty("jetty.base"));
        if (jettyBase != null)
        {
            File work = new File(jettyBase, "work");
            if (work.exists() && work.isDirectory() && work.canWrite())
            {
                context.setPersistTempDirectory(true);
                makeTempDirectory(work, context);
                return;
            }
        }

        //Make a temp directory in java.io.tmpdir
        makeTempDirectory(new File(System.getProperty("java.io.tmpdir")), context);
    }

    /**
     * Given an Object, return File reference for object.
     * Typically used to convert anonymous Object from getAttribute() calls to a File object.
     *
     * @param fileattr the file attribute to analyze and return from (supports type File, Path, and String).
     * @return the File object if it can be converted otherwise null.
     */
    private File asFile(Object fileattr)
    {
        if (fileattr == null)
            return null;
        if (fileattr instanceof File)
            return (File)fileattr;
        if (fileattr instanceof String)
            return new File((String)fileattr);
        if (fileattr instanceof Path)
            return ((Path)fileattr).toFile();

        return null;
    }

    public void makeTempDirectory(File parent, WebAppContext context)
        throws Exception
    {
        if (parent == null || !parent.exists() || !parent.canWrite() || !parent.isDirectory())
            throw new IllegalStateException("Parent for temp dir not configured correctly: " + (parent == null ? "null" : "writeable=" + parent.canWrite()));

        //Create a name for the webapp     
        String temp = getCanonicalNameForWebAppTmpDir(context);
        File tmpDir = null;
        if (context.isPersistTempDirectory())
        {
            //if it is to be persisted, make sure it will be the same name
            //by not using File.createTempFile, which appends random digits
            tmpDir = new File(parent, temp);
            configureTempDirectory(tmpDir, context);
        }
        else
        {
            // ensure dir will always be unique by having classlib generate random path name
            tmpDir = Files.createTempDirectory(parent.toPath(), temp).toFile();
            tmpDir.deleteOnExit();
            ensureTempDirUsable(tmpDir);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Set temp dir " + tmpDir);
        context.setTempDirectory(tmpDir);
    }

    public void configureTempDirectory(File dir, WebAppContext context)
    {
        if (dir == null)
            throw new IllegalArgumentException("Null temp dir");

        // if dir exists and we don't want it persisted, delete it
        if (!context.isPersistTempDirectory() && dir.exists() && !IO.delete(dir))
        {
            throw new IllegalStateException("Failed to delete temp dir " + dir);
        }

        // if it doesn't exist make it
        if (!dir.exists())
        {
            if (!dir.mkdirs())
            {
                throw new IllegalStateException("Unable to create temp dir " + dir);
            }
        }

        if (!context.isPersistTempDirectory())
            dir.deleteOnExit();

        ensureTempDirUsable(dir);
    }

    private void ensureTempDirUsable(File dir)
    {
        // is it useable
        if (!dir.canWrite() || !dir.isDirectory())
            throw new IllegalStateException("Temp dir " + dir + " not useable: writeable=" + dir.canWrite() + ", dir=" + dir.isDirectory());
    }

    public void unpack(WebAppContext context) throws IOException
    {
        Resource webApp = context.getBaseResource();
        _preUnpackBaseResource = context.getBaseResource();

        if (webApp == null)
        {
            String war = context.getWar();
            if (war != null && war.length() > 0)
                webApp = context.newResource(war);
            else
                webApp = context.getBaseResource();

            if (webApp == null)
                throw new IllegalStateException("No resourceBase or war set for context");

            // Accept aliases for WAR files
            if (webApp.isAlias())
            {
                LOG.debug(webApp + " anti-aliased to " + webApp.getAlias());
                webApp = context.newResource(webApp.getAlias());
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Try webapp=" + webApp + ", exists=" + webApp.exists() + ", directory=" + webApp.isDirectory() + " file=" + (webApp.getFile()));

            // Track the original web_app Resource, as this could be a PathResource.
            // Later steps force the Resource to be a JarFileResource, which introduces
            // URLConnection caches in such a way that it prevents Hot Redeployment
            // on MS Windows.
            Resource originalWarResource = webApp;

            // Is the WAR usable directly?
            if (webApp.exists() && !webApp.isDirectory() && !webApp.toString().startsWith("jar:"))
            {
                // No - then lets see if it can be turned into a jar URL.
                Resource jarWebApp = JarResource.newJarResource(webApp);
                if (jarWebApp.exists() && jarWebApp.isDirectory())
                    webApp = jarWebApp;
            }

            // If we should extract or the URL is still not usable
            if (webApp.exists() && (
                (context.isCopyWebDir() && webApp.getFile() != null && webApp.getFile().isDirectory()) ||
                    (context.isExtractWAR() && webApp.getFile() != null && !webApp.getFile().isDirectory()) ||
                    (context.isExtractWAR() && webApp.getFile() == null) ||
                    !webApp.isDirectory())
            )
            {
                // Look for sibling directory.
                File extractedWebAppDir = null;

                if (war != null)
                {
                    // look for a sibling like "foo/" to a "foo.war"
                    File warfile = Resource.newResource(war).getFile();
                    if (warfile != null && warfile.getName().toLowerCase(Locale.ENGLISH).endsWith(".war"))
                    {
                        File sibling = new File(warfile.getParent(), warfile.getName().substring(0, warfile.getName().length() - 4));
                        if (sibling.exists() && sibling.isDirectory() && sibling.canWrite())
                            extractedWebAppDir = sibling;
                    }
                }

                if (extractedWebAppDir == null)
                    // Then extract it if necessary to the temporary location
                    extractedWebAppDir = new File(context.getTempDirectory(), "webapp");

                if (webApp.getFile() != null && webApp.getFile().isDirectory())
                {
                    // Copy directory
                    LOG.debug("Copy " + webApp + " to " + extractedWebAppDir);
                    webApp.copyTo(extractedWebAppDir);
                }
                else
                {
                    //Use a sentinel file that will exist only whilst the extraction is taking place.
                    //This will help us detect interrupted extractions.
                    File extractionLock = new File(context.getTempDirectory(), ".extract_lock");

                    if (!extractedWebAppDir.exists())
                    {
                        //it hasn't been extracted before so extract it
                        extractionLock.createNewFile();
                        extractedWebAppDir.mkdir();
                        LOG.debug("Extract " + webApp + " to " + extractedWebAppDir);
                        Resource jarWebApp = JarResource.newJarResource(webApp);
                        jarWebApp.copyTo(extractedWebAppDir);
                        extractionLock.delete();
                    }
                    else
                    {
                        // Only extract if the war file is newer, or a .extract_lock file is left behind meaning a possible partial extraction
                        // Use the original War Resource to obtain lastModified to avoid filesystem locks on MS Windows.
                        if (originalWarResource.lastModified() > extractedWebAppDir.lastModified() || extractionLock.exists())
                        {
                            extractionLock.createNewFile();
                            IO.delete(extractedWebAppDir);
                            extractedWebAppDir.mkdir();
                            LOG.debug("Extract " + webApp + " to " + extractedWebAppDir);
                            Resource jarWebApp = JarResource.newJarResource(webApp);
                            jarWebApp.copyTo(extractedWebAppDir);
                            extractionLock.delete();
                        }
                    }
                }
                webApp = Resource.newResource(extractedWebAppDir.getCanonicalPath());
            }

            // Now do we have something usable?
            if (!webApp.exists() || !webApp.isDirectory())
            {
                LOG.warn("Web application not found " + war);
                throw new java.io.FileNotFoundException(war);
            }

            context.setBaseResource(webApp);

            if (LOG.isDebugEnabled())
                LOG.debug("webapp=" + webApp);
        }

        // Do we need to extract WEB-INF/lib?
        if (context.isCopyWebInf() && !context.isCopyWebDir())
        {
            Resource webInf = webApp.addPath("WEB-INF/");

            File extractedWebInfDir = new File(context.getTempDirectory(), "webinf");
            if (extractedWebInfDir.exists())
                IO.delete(extractedWebInfDir);
            extractedWebInfDir.mkdir();
            Resource webInfLib = webInf.addPath("lib/");
            File webInfDir = new File(extractedWebInfDir, "WEB-INF");
            webInfDir.mkdir();

            if (webInfLib.exists())
            {
                File webInfLibDir = new File(webInfDir, "lib");
                if (webInfLibDir.exists())
                    IO.delete(webInfLibDir);
                webInfLibDir.mkdir();

                LOG.debug("Copying WEB-INF/lib " + webInfLib + " to " + webInfLibDir);
                webInfLib.copyTo(webInfLibDir);
            }

            Resource webInfClasses = webInf.addPath("classes/");
            if (webInfClasses.exists())
            {
                File webInfClassesDir = new File(webInfDir, "classes");
                if (webInfClassesDir.exists())
                    IO.delete(webInfClassesDir);
                webInfClassesDir.mkdir();
                LOG.debug("Copying WEB-INF/classes from " + webInfClasses + " to " + webInfClassesDir.getAbsolutePath());
                webInfClasses.copyTo(webInfClassesDir);
            }

            webInf = Resource.newResource(extractedWebInfDir.getCanonicalPath());

            ResourceCollection rc = new ResourceCollection(webInf, webApp);

            if (LOG.isDebugEnabled())
                LOG.debug("context.resourcebase = " + rc);

            context.setBaseResource(rc);
        }
    }

    /**
     * Create a canonical name for a webapp temp directory.
     * <p>
     * The form of the name is:
     *
     * <pre>"jetty-"+host+"-"+port+"-"+resourceBase+"-_"+context+"-"+virtualhost+"-"+randomdigits+".dir"</pre>
     *
     * host and port uniquely identify the server
     * context and virtual host uniquely identify the webapp
     * randomdigits ensure every tmp directory is unique
     *
     * @param context the context to get the canonical name from
     * @return the canonical name for the webapp temp directory
     */
    public static String getCanonicalNameForWebAppTmpDir(WebAppContext context)
    {
        StringBuffer canonicalName = new StringBuffer();
        canonicalName.append("jetty-");

        //get the host and the port from the first connector
        Server server = context.getServer();
        if (server != null)
        {
            Connector[] connectors = context.getServer().getConnectors();

            if (connectors.length > 0)
            {
                //Get the host
                String host = null;
                int port = 0;
                if (connectors != null && (connectors[0] instanceof NetworkConnector))
                {
                    NetworkConnector connector = (NetworkConnector)connectors[0];
                    host = connector.getHost();
                    port = connector.getLocalPort();
                    if (port < 0)
                        port = connector.getPort();
                }
                if (host == null)
                    host = "0.0.0.0";
                canonicalName.append(host);

                //Get the port
                canonicalName.append("-");

                //if not available (eg no connectors or connector not started),
                //try getting one that was configured.
                canonicalName.append(port);
                canonicalName.append("-");
            }
        }

        // Resource base
        try
        {
            Resource resource = context.getBaseResource();
            if (resource == null)
            {
                if (context.getWar() == null || context.getWar().length() == 0)
                    throw new IllegalStateException("No resourceBase or war set for context");

                // Set dir or WAR to resource
                resource = context.newResource(context.getWar());
            }

            String resourceBaseName = getResourceBaseName(resource);
            canonicalName.append(resourceBaseName);
            canonicalName.append("-");
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Can't get resource base name", e);
            }
            canonicalName.append("-"); // empty resourceBaseName segment
        }

        //Context name
        canonicalName.append(context.getContextPath());

        //Virtual host (if there is one)
        canonicalName.append("-");
        String[] vhosts = context.getVirtualHosts();
        if (vhosts == null || vhosts.length <= 0)
            canonicalName.append("any");
        else
            canonicalName.append(vhosts[0]);

        canonicalName.append("-");

        return StringUtil.sanitizeFileSystemName(canonicalName.toString());
    }

    protected static String getResourceBaseName(Resource resource)
    {
        // Use File System and File interface if present
        try
        {
            File resourceFile = resource.getFile();
            if ((resourceFile != null) && (resource instanceof JarFileResource))
            {
                resourceFile = ((JarFileResource)resource).getJarFile();
            }

            if (resourceFile != null)
            {
                return resourceFile.getName();
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Resource has no File reference: {}", resource);
            }
        }

        // Use URI itself.
        URI uri = resource.getURI();
        if (uri == null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Resource has no URI reference: {}", resource);
            }
            return "";
        }

        return URIUtil.getUriLastPathSegment(uri);
    }

    protected List<Resource> findClassDirs(WebAppContext context)
        throws Exception
    {
        if (context == null)
            return null;

        List<Resource> classDirs = new ArrayList<>();

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
        List<Resource> jarResources = new ArrayList<>();
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
     * @return the list of jars as {@link Resource}, or null
     * @throws Exception if unable to scan for lib jars
     */
    protected List<Resource> findWebInfLibJars(WebAppContext context)
        throws Exception
    {
        Resource webInf = context.getWebInf();
        if (webInf == null || !webInf.exists())
            return null;

        List<Resource> jarResources = new ArrayList<>();
        Resource webInfLib = webInf.addPath("/lib");
        if (webInfLib.exists() && webInfLib.isDirectory())
        {
            String[] files = webInfLib.list();
            if (files != null)
            {
                Arrays.sort(files);
            }
            for (int f = 0; files != null && f < files.length; f++)
            {
                try
                {
                    Resource file = webInfLib.addPath(files[f]);
                    String fnlc = file.getName().toLowerCase(Locale.ENGLISH);
                    int dot = fnlc.lastIndexOf('.');
                    String extension = (dot < 0 ? null : fnlc.substring(dot));
                    if (extension != null && (extension.equals(".jar") || extension.equals(".zip")))
                    {
                        jarResources.add(file);
                    }
                }
                catch (Exception ex)
                {
                    LOG.warn(Log.EXCEPTION, ex);
                }
            }
        }
        return jarResources;
    }

    /**
     * Get jars from WebAppContext.getExtraClasspath as resources
     *
     * @param context the context to find extra classpath jars in
     * @return the list of Resources with the extra classpath, or null if not found
     * @throws Exception if unable to find the extra classpath jars
     */
    protected List<Resource> findExtraClasspathJars(WebAppContext context)
        throws Exception
    {
        if (context == null || context.getExtraClasspath() == null)
            return null;

        List<Resource> jarResources = new ArrayList<>();
        StringTokenizer tokenizer = new StringTokenizer(context.getExtraClasspath(), ",;");
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken().trim();

            // Is this a Glob Reference?
            if (isGlobReference(token))
            {
                String dir = token.substring(0, token.length() - 2);
                // Use directory
                Resource dirResource = context.newResource(dir);
                if (dirResource.exists() && dirResource.isDirectory())
                {
                    // To obtain the list of files
                    String[] entries = dirResource.list();
                    if (entries != null)
                    {
                        Arrays.sort(entries);
                        for (String entry : entries)
                        {
                            try
                            {
                                Resource fileResource = dirResource.addPath(entry);
                                if (isFileSupported(fileResource))
                                {
                                    jarResources.add(fileResource);
                                }
                            }
                            catch (Exception ex)
                            {
                                LOG.warn(Log.EXCEPTION, ex);
                            }
                        }
                    }
                }
            }
            else
            {
                // Simple reference, add as-is
                Resource resource = context.newResource(token);
                if (isFileSupported(resource))
                {
                    jarResources.add(resource);
                }
            }
        }

        return jarResources;
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
            Resource classes = webInf.addPath("classes/");
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
     * @throws Exception if unable to find the extra classpaths
     */
    protected List<Resource> findExtraClasspathDirs(WebAppContext context)
        throws Exception
    {
        if (context == null || context.getExtraClasspath() == null)
            return null;

        List<Resource> dirResources = new ArrayList<>();
        StringTokenizer tokenizer = new StringTokenizer(context.getExtraClasspath(), ",;");
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken().trim();
            // ignore glob references, they only refer to lists of jars/zips anyway
            if (!isGlobReference(token))
            {
                Resource resource = context.newResource(token);
                if (resource.exists() && resource.isDirectory())
                {
                    dirResources.add(resource);
                }
            }
        }

        return dirResources;
    }

    private boolean isGlobReference(String token)
    {
        return token.endsWith("/*") || token.endsWith("\\*");
    }

    private boolean isFileSupported(Resource resource)
    {
        String filenameLowercase = resource.getName().toLowerCase(Locale.ENGLISH);
        int dot = filenameLowercase.lastIndexOf('.');
        String extension = (dot < 0 ? null : filenameLowercase.substring(dot));
        return (extension != null && (extension.equals(".jar") || extension.equals(".zip")));
    }
}
