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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.MountedPathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(WebInfConfiguration.class);

    public static final String TEMPDIR_CONFIGURED = "org.eclipse.jetty.tmpdirConfigured";
    public static final String TEMPORARY_RESOURCE_BASE = "org.eclipse.jetty.webapp.tmpResourceBase";

    protected Resource _preUnpackBaseResource;

    public WebInfConfiguration()
    {
    }

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        // Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);

        // Extract webapp if necessary
        unpack(context);
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        Resource webInf = context.getWebInf();

        // Add WEB-INF classes and lib classpaths
        if (webInf != null && webInf.isDirectory() && context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes = webInf.resolve("classes/");
            if (classes.exists())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes);

            // Look for jars
            Resource lib = webInf.resolve("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
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
        //TODO there is something wrong with the config of the resource base as this should never be null
        context.setBaseResource(_preUnpackBaseResource == null ? null : _preUnpackBaseResource);
    }

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
     * Iff jakarta.servlet.context.tempdir context attribute is set for
     * this webapp &amp;&amp; exists &amp;&amp; writeable, then use it. Set delete on exit depends on
     * value of persistTempDirectory.
     * </li>
     * </ol>
     *
     * <p>
     * B. Create a directory based on global settings. The new directory
     * will be called <code>"Jetty-"+host+"-"+port+"__"+context+"-"+virtualhost+"-"+randomdigits+".dir"</code>
     * <p>
     * If the user has specified the context attribute {@link Server#BASE_TEMP_DIR_ATTR}, the
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

        // No temp directory configured, try to establish one via the jakarta.servlet.context.tempdir.
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
                throw new IllegalStateException(WebAppContext.BASETEMPDIR + " is not a writable directory");

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
            LOG.debug("Set temp dir {}", tmpDir);
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

            // Use real location (if different) for WAR file, so that change/modification monitoring can work.
            URI targetURI = webApp.getTargetURI();
            if (targetURI != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} anti-aliased to {}", webApp, targetURI);
                webApp = context.newResource(targetURI);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Try webapp={} exists={} directory={} file={}", webApp, webApp.exists(), webApp.isDirectory(), webApp.getPath());

            // Track the original web_app Resource, as this could be a PathResource.
            // Later steps force the Resource to be a JarFileResource, which introduces
            // URLConnection caches in such a way that it prevents Hot Redeployment
            // on MS Windows.
            Resource originalWarResource = webApp;

            // Is the WAR usable directly?
            if (webApp.exists() && !webApp.isDirectory() && !webApp.toString().startsWith("jar:"))
            {
                // No - then lets see if it can be turned into a jar URL.
                webApp = context.getResourceFactory().newJarFileResource(webApp.getURI());
            }

            // If we should extract or the URL is still not usable
            if (webApp.exists() && (
                (context.isCopyWebDir() && webApp.getPath() != null && originalWarResource.isDirectory()) ||
                    (context.isExtractWAR() && webApp.getPath() != null && !originalWarResource.isDirectory()) ||
                    (context.isExtractWAR() && webApp.getPath() == null) ||
                    !originalWarResource.isDirectory())
            )
            {
                // Look for sibling directory.
                Path extractedWebAppDir = null;

                if (war != null)
                {
                    // look for a sibling like "foo/" to a "foo.war"
                    Path warfile = context.getResourceFactory().newResource(war).getPath();
                    if (warfile != null && warfile.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".war"))
                    {
                        Path sibling = warfile.getParent().resolve(warfile.getFileName().toString().substring(0, warfile.getFileName().toString().length() - 4));
                        if (Files.exists(sibling) && Files.isDirectory(sibling) && Files.isWritable(sibling))
                            extractedWebAppDir = sibling;
                    }
                }

                if (extractedWebAppDir == null)
                {
                    // Then extract it if necessary to the temporary location
                    extractedWebAppDir = context.getTempDirectory().toPath().resolve("webapp");
                    context.setAttribute(TEMPORARY_RESOURCE_BASE, extractedWebAppDir);
                }

                if (webApp.getPath() != null && webApp.isDirectory())
                {
                    // Copy directory
                    if (LOG.isDebugEnabled())
                        LOG.debug("Copy {} to  {}", webApp, extractedWebAppDir);
                    webApp.copyTo(extractedWebAppDir);
                }
                else
                {
                    //Use a sentinel file that will exist only whilst the extraction is taking place.
                    //This will help us detect interrupted extractions.
                    File extractionLock = new File(context.getTempDirectory(), ".extract_lock");

                    if (!Files.exists(extractedWebAppDir))
                    {
                        //it hasn't been extracted before so extract it
                        extractionLock.createNewFile();
                        Files.createDirectory(extractedWebAppDir);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Extract {} to {}", webApp, extractedWebAppDir);
                        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
                        {
                            Resource jarWebApp = resourceFactory.newResource(webApp.getPath());
                            jarWebApp.copyTo(extractedWebAppDir);
                        }
                        extractionLock.delete();
                    }
                    else
                    {
                        // Only extract if the war file is newer, or a .extract_lock file is left behind meaning a possible partial extraction
                        // Use the original War Resource to obtain lastModified to avoid filesystem locks on MS Windows.
                        if (originalWarResource.lastModified().isAfter(Files.getLastModifiedTime(extractedWebAppDir).toInstant()) || extractionLock.exists())
                        {
                            extractionLock.createNewFile();
                            IO.delete(extractedWebAppDir);
                            Files.createDirectory(extractedWebAppDir);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Extract {} to {}", webApp, extractedWebAppDir);
                            try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
                            {
                                Resource jarWebApp = resourceFactory.newResource(webApp.getPath());
                                jarWebApp.copyTo(extractedWebAppDir);
                            }
                            extractionLock.delete();
                        }
                    }
                }
                webApp = context.getResourceFactory().newResource(extractedWebAppDir.normalize());
            }

            // Now do we have something usable?
            if (!webApp.exists() || !webApp.isDirectory())
            {
                LOG.warn("Web application not found {}", war);
                throw new java.io.FileNotFoundException(war);
            }

            context.setBaseResource(webApp);

            if (LOG.isDebugEnabled())
                LOG.debug("webapp={}", webApp);
        }

        // Do we need to extract WEB-INF/lib?
        if (context.isCopyWebInf() && !context.isCopyWebDir())
        {
            Resource webInf = webApp.resolve("WEB-INF/");

            File extractedWebInfDir = new File(context.getTempDirectory(), "webinf");
            if (extractedWebInfDir.exists())
                IO.delete(extractedWebInfDir);
            extractedWebInfDir.mkdir();
            Resource webInfLib = webInf.resolve("lib/");
            File webInfDir = new File(extractedWebInfDir, "WEB-INF");
            webInfDir.mkdir();

            if (webInfLib.exists())
            {
                File webInfLibDir = new File(webInfDir, "lib");
                if (webInfLibDir.exists())
                    IO.delete(webInfLibDir);
                webInfLibDir.mkdir();

                if (LOG.isDebugEnabled())
                    LOG.debug("Copying WEB-INF/lib {} to {}", webInfLib, webInfLibDir);
                webInfLib.copyTo(webInfLibDir.toPath());
            }

            Resource webInfClasses = webInf.resolve("classes/");
            if (webInfClasses.exists())
            {
                File webInfClassesDir = new File(webInfDir, "classes");
                if (webInfClassesDir.exists())
                    IO.delete(webInfClassesDir);
                webInfClassesDir.mkdir();
                if (LOG.isDebugEnabled())
                    LOG.debug("Copying WEB-INF/classes from {} to {}", webInfClasses, webInfClassesDir.getAbsolutePath());
                webInfClasses.copyTo(webInfClassesDir.toPath());
            }

            webInf = context.getResourceFactory().newResource(extractedWebInfDir.getCanonicalPath());

            Resource rc = Resource.combine(webInf, webApp);

            if (LOG.isDebugEnabled())
                LOG.debug("context.baseResource={}", rc);

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
                LOG.debug("Can't get resource base name", e);

            canonicalName.append("-"); // empty resourceBaseName segment
        }

        //Context name
        String contextPath = context.getContextPath();
        contextPath = contextPath.replace('/', '_');
        contextPath = contextPath.replace('\\', '_');
        canonicalName.append(contextPath);

        //Virtual host (if there is one)
        canonicalName.append("-");
        List<String> vhosts = context.getVirtualHosts();
        if (vhosts == null || vhosts.size() <= 0)
            canonicalName.append("any");
        else
            canonicalName.append(vhosts.get(0));

        // sanitize
        for (int i = 0; i < canonicalName.length(); i++)
        {
            char c = canonicalName.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && "-.".indexOf(c) < 0)
                canonicalName.setCharAt(i, '.');
        }

        canonicalName.append("-");

        return StringUtil.sanitizeFileSystemName(canonicalName.toString());
    }

    protected static String getResourceBaseName(Resource resource)
    {
        // Use File System and File interface if present
        Path resourceFile = resource.getPath();

        if ((resourceFile != null) && (resource instanceof MountedPathResource))
        {
            resourceFile = ((MountedPathResource)resource).getContainerPath();
        }

        if (resourceFile != null)
        {
            Path fileName = resourceFile.getFileName();
            return fileName == null ? "" : fileName.toString();
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
}
