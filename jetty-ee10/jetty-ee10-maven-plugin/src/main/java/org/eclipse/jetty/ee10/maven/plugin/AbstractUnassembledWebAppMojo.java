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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Base class for all goals that operate on unassembled webapps.
 *
 */
public abstract class AbstractUnassembledWebAppMojo extends AbstractWebAppMojo
{
    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter (defaultValue = "${project.baseDir}/src/main/webapp/WEB-INF/web.xml")
    protected File webXml;
    
    /**
     * The directory containing generated test classes.
     * 
     */
    @Parameter (defaultValue = "${project.build.testOutputDirectory}", required = true)
    protected File testClassesDirectory;
    
    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     */
    @Parameter
    protected ScanPattern scanTestClassesPattern;

    /**
     * The directory containing generated classes.
     */
    @Parameter (defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;
    

    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     */
    @Parameter
    protected ScanPattern scanClassesPattern;
    

    /**
     * Default root directory for all html/jsp etc files.
     * Used to initialize webApp.setBaseResource().
     */
    @Parameter (defaultValue = "${project.basedir}/src/main/webapp", readonly = true)
    protected File webAppSourceDirectory;
    
    protected void verifyPomConfiguration() throws MojoExecutionException
    {        
        //Does the default webapp static resource location exist?
        if (!webAppSourceDirectory.exists())
        {  
            //try last resort of a fake directory
            File target = new File(project.getBuild().getDirectory());
            webAppSourceDirectory = new File(target, FAKE_WEBAPP);           
        }

        // check the classes to form a classpath with
        try
        {
            //allow a webapp with no classes in it (just jsps/html)
            if (classesDirectory != null)
            {
                if (!classesDirectory.exists())
                    getLog().info("Classes directory " + classesDirectory.getCanonicalPath() + " does not exist");
                else
                    getLog().info("Classes = " + classesDirectory.getCanonicalPath());
            }
            else
                getLog().info("Classes directory not set");         
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Location of classesDirectory does not exist");
        }
    }
    
    @Override
    protected void configureWebApp() throws AbstractMojoExecutionException
    {
        super.configureWebApp();
        try
        {
            configureUnassembledWebApp();
        }
        catch (IOException e)
        {
            throw new MojoFailureException("Unable to configure unassembled webapp", e);
        }
    }
    
    /**
     * Configure a webapp that has not been assembled into a war. 
     * 
     * @throws IOException
     */
    protected void configureUnassembledWebApp() throws IOException
    {   
        //Set up the location of the webapp.
        //There are 2 parts to this: setWar() and setBaseResource(). On standalone jetty,
        //the former could be the location of a packed war, while the latter is the location
        //after any unpacking. With this mojo, you are running an unpacked, unassembled webapp,
        //so the two locations should be equal.

        //The first time we run, remember the original base dir
        if (originalBaseResource == null)
        {
            if (webApp.getBaseResource() == null)
            {
                //Use the default static resource location
                if (!webAppSourceDirectory.exists())
                    webAppSourceDirectory.mkdirs();
                originalBaseResource = webApp.getResourceFactory().newResource(webAppSourceDirectory.getCanonicalPath());
            }
            else
                originalBaseResource = webApp.getBaseResource();
        }

        //On every subsequent re-run set it back to the original base dir before
        //we might have applied any war overlays onto it
        
        webApp.setBaseResource(originalBaseResource);

        if (webApp.getWar() == null)
            webApp.setWar(originalBaseResource.getURI().toURL().toExternalForm());
        
        if (classesDirectory != null)
            webApp.setClasses(classesDirectory);

        if (useTestScope && (testClassesDirectory != null))
            webApp.setTestClasses(testClassesDirectory);

        List<File> webInfLibs = getWebInfLibArtifacts().stream()
            .map(a ->
            {
                Path p = mavenProjectHelper.getPathFor(a);
                getLog().debug("Artifact " + a.getId() + " loaded from " + p + " added to WEB-INF/lib");
                return p.toFile();
            }).collect(Collectors.toList());

        webApp.setWebInfLib(webInfLibs);

        //if we have not already set web.xml location, need to set one up
        if (webApp.getDescriptor() == null)
        {
            //Has an explicit web.xml file been configured to use?
            if (webXml != null)
            {
                Resource r = webApp.getResourceFactory().newResource(webXml.toPath());
                if (r.exists() && !r.isDirectory())
                {
                    webApp.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
            if (webApp.getDescriptor() == null && webApp.getBaseResource() != null)
            {
                // TODO: should never return from WEB-INF/lib/foo.jar!/WEB-INF/web.xml
                // TODO: should also never return from a META-INF/versions/#/WEB-INF/web.xml location
                Resource r = webApp.getBaseResource().resolve("WEB-INF/web.xml");
                if (r.exists() && !r.isDirectory())
                {
                    webApp.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: finally try the configured static resource directory if there is one
            if (webApp.getDescriptor() == null && (webAppSourceDirectory != null))
            {
                // TODO: fix, use Resource or Path
                File f = new File(new File(webAppSourceDirectory, "WEB-INF"), "web.xml");
                if (f.exists() && f.isFile())
                {
                    webApp.setDescriptor(f.getCanonicalPath());
                }
            }
        }

        //process any overlays and the war type artifacts, and
        //sets up the base resource collection for the webapp
        mavenProjectHelper.getOverlayManager().applyOverlays(webApp);

        getLog().info("web.xml file = " + webApp.getDescriptor());       
        getLog().info("Webapp directory = " + webAppSourceDirectory.getCanonicalPath());
        getLog().info("Web defaults = " + (webApp.getDefaultsDescriptor() == null ? " jetty default" : webApp.getDefaultsDescriptor()));
        getLog().info("Web overrides = " + (webApp.getOverrideDescriptor() == null ? " none" : webApp.getOverrideDescriptor()));
    }

    /**
     * Find which dependencies are suitable for addition to the virtual
     * WEB-INF lib.
     */
    protected Collection<Artifact> getWebInfLibArtifacts()
    {
        return project.getArtifacts().stream()
            .filter(this::isArtifactOKForWebInfLib)
            .collect(Collectors.toList());
    }
    
    /**
     * Check if the artifact is suitable to be considered part of the
     * virtual web-inf/lib.
     * 
     * @param artifact the artifact to check
     * @return true if the artifact represents a jar, isn't scope provided and 
     * is scope test, if useTestScope is enabled. False otherwise.
     */
    private boolean isArtifactOKForWebInfLib(Artifact artifact)
    {
        //The dependency cannot be of type war
        if ("war".equalsIgnoreCase(artifact.getType()))
            return false;

        //The dependency cannot be scope provided (those should be added to the plugin classpath)
        if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
            return false;

        //Test dependencies not added by default
        if (Artifact.SCOPE_TEST.equals(artifact.getScope()) && !useTestScope)
            return false;

        return true;
    }
}
