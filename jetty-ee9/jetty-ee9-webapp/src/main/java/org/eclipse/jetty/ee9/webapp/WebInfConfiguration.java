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
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.MountedPathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(WebInfConfiguration.class);

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

        // Force early configuration (clearing) of the temporary directory so we can unpack into it.
        context.getCoreContextHandler().createTempDirectory();

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
            if (Resources.isReadableDirectory(classes))
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes);

            // Look for jars
            Resource lib = webInf.resolve("lib/");
            if (Resources.isReadableDirectory(lib))
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
        }
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        //reset the base resource back to what it was before we did any unpacking of resources
        context.setBaseResource(_preUnpackBaseResource);
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
     * Iff {@value jakarta.servlet.ServletContext#TEMPDIR} context attribute is set for
     * this webapp &amp;&amp; exists &amp;&amp; writeable, then use it. Set delete on exit depends on
     * value of persistTempDirectory.
     * </li>
     * </ol>
     *
     * <p>
     * B. Create a directory based on global settings. The new directory
     * will be called <code>"Jetty-"+host+"-"+port+"__"+context+"-"+virtualhost+"-"+randomdigits+".dir"</code>
     * If the temporary directory is persistent, then the random digits are not added to the name.
     * The {@link Server#getTempDirectory()} is used for the parent of a created temporary directory.
     * </p>
     *
     * @param context the context to resolve the temp directory from
     * @throws Exception if unable to resolve the temp directory
     */
    public void resolveTempDirectory(WebAppContext context)
        throws Exception
    {
        //If a tmp directory is already set we should use it
        File tempDirectory = context.getTempDirectory();
        if (tempDirectory != null)
        {
            return;
        }

        // No temp directory configured, try to establish one via the jakarta.servlet.context.tempdir.
        File servletTmpDir = IO.asFile(context.getAttribute(ServletContext.TEMPDIR));
        if (servletTmpDir != null)
        {
            // Use as tmpDir
            tempDirectory = servletTmpDir;
            // Set as TempDir in context.
            context.setTempDirectory(tempDirectory);
            return;
        }

        context.makeTempDirectory();
    }

    @Deprecated(forRemoval = true, since = "12.0.12")
    public void makeTempDirectory(File parent, WebAppContext context)
        throws Exception
    {
        context.makeTempDirectory();
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
            if (webApp.isAlias())
            {
                URI realURI = webApp.getRealURI();
                if (LOG.isDebugEnabled())
                    LOG.debug("{} anti-aliased to {}", webApp, realURI);
                Resource realWebApp = context.newResource(realURI);
                if (realWebApp != null && realWebApp.exists())
                    webApp = realWebApp;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Try webapp={} exists={} directory={} file={}", webApp, webApp.exists(), webApp.isDirectory(), webApp.getPath());

            // Track the original web_app Resource, as this could be a PathResource.
            // Later steps force the Resource to be a JarFileResource, which introduces
            // URLConnection caches in such a way that it prevents Hot Redeployment
            // on MS Windows.
            Resource originalWarResource = webApp;

            // Is the WAR usable directly?
            if (Resources.isReadableFile(webApp) && FileID.isArchive(webApp.getURI()) && !webApp.getURI().getScheme().equalsIgnoreCase("jar"))
            {
                // Turned this into a jar URL.
                Resource jarWebApp = context.getResourceFactory().newJarFileResource(webApp.getURI());
                if (Resources.isDirectory(jarWebApp))
                    webApp = jarWebApp;
            }

            // If we should extract or the URL is still not usable
            if (webApp.exists() && (
                (context.isCopyWebDir() && webApp.getPath() != null && originalWarResource.isDirectory()) ||
                    (context.isExtractWAR() && webApp.getPath() != null && !originalWarResource.isDirectory()) ||
                    (context.isExtractWAR() && webApp.getPath() == null) ||
                    !webApp.isDirectory())
            )
            {
                Path extractedWebAppDir = null;

                // If this is a war file, we should look for a sibling
                // directory of the same name
                if (war != null)
                {
                    // We have obtained the webApp from the war string, so it
                    // cannot be a CombinedResource, therefore safe to use it's Path
                    Path warPath = webApp.getPath();
                    if (warPath != null)
                    {
                        // look for a sibling like "foo/" to a "foo.war"
                        if (FileID.isWebArchive(warPath) && Files.exists(warPath))
                        {
                            Path sibling = warPath.getParent().resolve(FileID.getBasename(warPath));
                            if (Files.exists(sibling) && Files.isDirectory(sibling) && Files.isWritable(sibling))
                                extractedWebAppDir = sibling;
                        }
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
                            Resource jarWebApp = resourceFactory.newJarFileResource(webApp.getURI());
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
                            // Best effort delete
                            if (IO.delete(extractedWebAppDir))
                            {
                                // Recreate the directory if it was deleted.
                                Files.createDirectory(extractedWebAppDir);
                            }
                            else
                            {
                                if (LOG.isInfoEnabled())
                                    LOG.info("Unable to delete path {}, reusing existing path", extractedWebAppDir);
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("Extract {} to {}", webApp, extractedWebAppDir);
                            try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
                            {
                                Resource jarWebApp = resourceFactory.newJarFileResource(webApp.getURI());
                                jarWebApp.copyTo(extractedWebAppDir);
                            }
                            extractionLock.delete();
                        }
                    }
                }
                webApp = context.getResourceFactory().newResource(extractedWebAppDir.normalize());
            }

            // Now do we have something usable?
            if (Resources.missing(webApp))
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

            if (Resources.isReadableDirectory(webInf))
            {
                File extractedWebInfDir = new File(context.getTempDirectory(), "webinf");
                if (extractedWebInfDir.exists())
                    IO.delete(extractedWebInfDir);
                extractedWebInfDir.mkdir();

                File webInfDir = new File(extractedWebInfDir, "WEB-INF");
                webInfDir.mkdir();

                Resource webInfLib = webInf.resolve("lib/");
                if (Resources.isReadableDirectory(webInfLib))
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
                if (Resources.isReadableDirectory(webInfClasses))
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
            }

            Resource rc = ResourceFactory.combine(webInf, webApp);

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
        return context.getCanonicalNameForTmpDir();
    }

    @Deprecated(forRemoval = true, since = "12.0.12")
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
