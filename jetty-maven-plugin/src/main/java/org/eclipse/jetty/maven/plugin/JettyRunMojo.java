//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.maven.plugin.helper.MavenProjectHelper;
import org.eclipse.jetty.maven.plugin.service.WebAppDependencyResolutionService;
import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * This goal is used in-situ on a Maven project without first requiring that the project
 * is assembled into a war, saving time during the development cycle.
 * <p>
 * The plugin forks a parallel lifecycle to ensure that the "compile" phase has been completed before invoking Jetty. This means
 * that you do not need to explicitly execute a "mvn compile" first. It also means that a "mvn clean jetty:run" will ensure that
 * a full fresh compile is done before invoking Jetty.
 * <p>
 * Once invoked, the plugin can be configured to run continuously, scanning for changes in the project and automatically performing a
 * hot redeploy when necessary. This allows the developer to concentrate on coding changes to the project using their IDE of choice and have those changes
 * immediately and transparently reflected in the running web container, eliminating development time that is wasted on rebuilding, reassembling and redeploying.
 * <p>
 * You may also specify the location of a jetty.xml file whose contents will be applied before any plugin configuration.
 * This can be used, for example, to deploy a static webapp that is not part of your maven build.
 * <p>
 * There is a <a href="http://www.eclipse.org/jetty/documentation/current/maven-and-jetty.html">reference guide</a> to the configuration parameters for this plugin.
 *
 * Runs jetty directly from a maven project
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JettyRunMojo extends AbstractJettyMojo
{
    public static final String DEFAULT_WEBAPP_SRC = "src" + File.separator + "main" + File.separator + "webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     */
    @Parameter(alias = "useTestClasspath", defaultValue = "false")
    protected boolean useTestScope;

    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter(defaultValue = "${maven.war.webxml}", readonly = true)
    protected String webXml;

    /**
     * The directory containing generated classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;

    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     */
    @Parameter
    protected ScanPattern scanClassesPattern;

    /**
     * The directory containing generated test classes.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    protected File testClassesDirectory;

    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     */
    @Parameter
    protected ScanPattern scanTestClassesPattern;

    /**
     * Root directory for all html/jsp etc files
     */
    @Parameter(defaultValue = "${maven.war.src}")
    protected File webAppSourceDirectory;

    /**
     * List of files or directories to additionally periodically scan for changes. Optional.
     */
    @Parameter
    protected File[] scanTargets;

    /**
     * List of directories with ant-style &lt;include&gt; and &lt;exclude&gt; patterns
     * for extra targets to periodically scan for changes. Can be used instead of,
     * or in conjunction with &lt;scanTargets&gt;.Optional.
     */
    @Parameter
    protected ScanTargetPattern[] scanTargetPatterns;

    protected Resource originalBaseResource;

    /**
     * maven project helper
     */
    protected MavenProjectHelper mavenProjectHelper;

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        mavenProjectHelper = new MavenProjectHelper(project);
        super.execute();
    }

    /**
     * Verify the configuration given in the pom.
     *
     * @see AbstractJettyMojo#checkPomConfiguration()
     */
    @Override
    public boolean checkPomConfiguration() throws MojoExecutionException
    {
        // check the location of the static content/jsps etc
        try
        {
            if ((webAppSourceDirectory == null) || !webAppSourceDirectory.exists())
            {
                getLog().info("webAppSourceDirectory" + (webAppSourceDirectory == null ? " not set." : (webAppSourceDirectory.getAbsolutePath() + " does not exist.")) + " Trying " + DEFAULT_WEBAPP_SRC);
                webAppSourceDirectory = retrieveWebAppSourceDirectory(project);
            }
            else
            {
                getLog().info("Webapp source directory = " + webAppSourceDirectory.getCanonicalPath());
            }
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Webapp source directory does not exist", e);
        }

        // check reload mechanic
        if (!"automatic".equalsIgnoreCase(reload) && !"manual".equalsIgnoreCase(reload))
        {
            throw new MojoExecutionException("invalid reload mechanic specified, must be 'automatic' or 'manual'");
        }
        else
        {
            getLog().info("Reload Mechanic: " + reload);
        }
        getLog().info("nonBlocking:" + nonBlocking);

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

        return true;
    }

    private File retrieveWebAppSourceDirectory(MavenProject project)
    {
        File webAppSourceDirectory = new File(project.getBasedir(), DEFAULT_WEBAPP_SRC);
        if (!webAppSourceDirectory.exists())
        {
            getLog().info("webAppSourceDirectory " + webAppSourceDirectory.getAbsolutePath() + " does not exist. Trying " + project.getBuild().getDirectory() + File.separator + FAKE_WEBAPP);

            //try last resort of making a fake empty dir
            File target = new File(project.getBuild().getDirectory());
            webAppSourceDirectory = new File(target, FAKE_WEBAPP);
            if (!webAppSourceDirectory.exists())
                webAppSourceDirectory.mkdirs();
        }
        return webAppSourceDirectory;
    }

    @Override
    public void finishConfigurationBeforeStart() throws Exception
    {
        server.setStopAtShutdown(true); //as we will normally be stopped with a cntrl-c, ensure server stopped 
        super.finishConfigurationBeforeStart();
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureWebApplication()
     */
    @Override
    public void configureWebApplication() throws Exception
    {
        super.configureWebApplication();

        //Set up the location of the webapp.
        //There are 2 parts to this: setWar() and setBaseResource(). On standalone jetty,
        //the former could be the location of a packed war, while the latter is the location
        //after any unpacking. With this mojo, you are running an unpacked, unassembled webapp,
        //so the two locations should be equal.
        Resource webAppSourceDirectoryResource = Resource.newResource(webAppSourceDirectory.getCanonicalPath());
        if (webApp.getWar() == null)
            webApp.setWar(webAppSourceDirectoryResource.toString());

        //The first time we run, remember the original base dir
        if (originalBaseResource == null)
        {
            if (webApp.getBaseResource() == null)
                originalBaseResource = webAppSourceDirectoryResource;
            else
                originalBaseResource = webApp.getBaseResource();
        }

        //On every subsequent re-run set it back to the original base dir before
        //we might have applied any war overlays onto it
        webApp.setBaseResource(originalBaseResource);

        // classes / testClasses
        if (classesDirectory != null)
        {
            webApp.setClasses(Resource.newResource(classesDirectory));
        }
        if (useTestScope && (testClassesDirectory != null))
        {
            webApp.setTestClasses(Resource.newResource(testClassesDirectory));
        }
        //if we have not already set web.xml location, need to set one up
        if (webApp.getDescriptor() == null)
        {
            webApp.setDescriptor(findWebDescription());
        }

        WebAppDependencyResolutionService webAppDependencyResolutionService = new WebAppDependencyResolutionService(project, useTestScope);
        webAppDependencyResolutionService.configureDependencies(webApp);
        getLog().info("web.xml file = " + webApp.getDescriptor());
        getLog().info("Webapp directory = " + webAppSourceDirectory.getCanonicalPath());
    }

    private String findWebDescription() throws IOException
    {
        //Has an explicit web.xml file been configured to use?
        if (webXml != null)
        {
            Resource r = Resource.newResource(webXml);
            if (r.exists() && !r.isDirectory())
            {
                return r.toString();
            }
        }

        //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
        if (webApp.getDescriptor() == null && webApp.getBaseResource() != null)
        {
            Resource r = webApp.getBaseResource().addPath("WEB-INF/web.xml");
            if (r.exists() && !r.isDirectory())
            {
                return r.toString();
            }
        }

        //Still don't have a web.xml file: finally try the configured static resource directory if there is one
        if (webApp.getDescriptor() == null && (webAppSourceDirectory != null))
        {
            File f = new File(new File(webAppSourceDirectory, "WEB-INF"), "web.xml");
            if (f.exists() && f.isFile())
            {
                return f.getCanonicalPath();
            }
        }
        return null;
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureScanner()
     */
    @Override
    public void configureScanner()
        throws MojoExecutionException
    {
        try
        {
            gatherScannables();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error forming scan list", e);
        }

        scanner.addListener(new PathWatcher.EventListListener()
        {

            @Override
            public void onPathWatchEvents(List<PathWatchEvent> events)
            {
                try
                {
                    boolean reconfigure = false;
                    if (events != null)
                    {
                        for (PathWatchEvent e : events)
                        {
                            if (e.getPath().equals(project.getFile().toPath()))
                            {
                                reconfigure = true;
                                break;
                            }
                        }
                    }

                    restartWebApp(reconfigure);
                }
                catch (Exception e)
                {
                    getLog().error("Error reconfiguring/restarting webapp after change in watched files", e);
                }
            }
        });
    }

    public void gatherScannables() throws Exception
    {
        if (webApp.getDescriptor() != null)
        {
            Resource r = Resource.newResource(webApp.getDescriptor());
            scanner.watch(r.getFile().toPath());
        }

        if (webApp.getJettyEnvXml() != null)
            scanner.watch(new File(webApp.getJettyEnvXml()).toPath());

        if (webApp.getDefaultsDescriptor() != null)
        {
            if (!WebAppContext.WEB_DEFAULTS_XML.equals(webApp.getDefaultsDescriptor()))
                scanner.watch(new File(webApp.getDefaultsDescriptor()).toPath());
        }

        if (webApp.getOverrideDescriptor() != null)
        {
            scanner.watch(new File(webApp.getOverrideDescriptor()).toPath());
        }

        File jettyWebXmlFile = findJettyWebXmlFile(new File(webAppSourceDirectory, "WEB-INF"));
        if (jettyWebXmlFile != null)
        {
            scanner.watch(jettyWebXmlFile.toPath());
        }

        //make sure each of the war artifacts is added to the scanner
        for (Artifact a : MavenProjectHelper.findWarOrZipArtifacts(project))
        {
            Path artifactPath = mavenProjectHelper.getArtifactPath(a);
            scanner.watch(artifactPath);
        }
        for (Resource file : webApp.getWebInfLib())
        {
            scanner.watch(file.getFile().toPath());
        }
        for (Resource webInfClass : webApp.getWebInfClasses())
        {
            scanner.watch(webInfClass.getFile().toPath());
        }

        //handle the explicit extra scan targets
        if (scanTargets != null)
        {
            for (File f : scanTargets)
            {
                if (f.isDirectory())
                {
                    PathWatcher.Config config = new PathWatcher.Config(f.toPath());
                    config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                    scanner.watch(config);
                }
                else
                    scanner.watch(f.toPath());
            }
        }

        //handle the extra scan patterns
        if (scanTargetPatterns != null)
        {
            for (ScanTargetPattern p : scanTargetPatterns)
            {
                PathWatcher.Config config = new PathWatcher.Config(p.getDirectory().toPath());
                config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                for (String pattern : p.getExcludes())
                {
                    config.addExcludeGlobRelative(pattern);
                }
                for (String pattern : p.getIncludes())
                {
                    config.addIncludeGlobRelative(pattern);
                }
                scanner.watch(config);
            }
        }

        scanner.watch(project.getFile().toPath());

        if (webApp.getTestClasses() != null && webApp.getTestClasses().exists())
        {
            PathWatcher.Config config = new PathWatcher.Config(webApp.getTestClasses().getFile().toPath());
            config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
            if (scanTestClassesPattern != null)
            {
                for (String p : scanTestClassesPattern.getExcludes())
                {
                    config.addExcludeGlobRelative(p);
                }
                for (String p : scanTestClassesPattern.getIncludes())
                {
                    config.addIncludeGlobRelative(p);
                }
            }
            scanner.watch(config);
        }

        if (webApp.getClasses() != null && webApp.getClasses().exists())
        {
            PathWatcher.Config config = new PathWatcher.Config(webApp.getClasses().getFile().toPath());
            config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
            if (scanClassesPattern != null)
            {
                for (String p : scanClassesPattern.getExcludes())
                {
                    config.addExcludeGlobRelative(p);
                }

                for (String p : scanClassesPattern.getIncludes())
                {
                    config.addIncludeGlobRelative(p);
                }
            }
            scanner.watch(config);
        }

        if (webApp.getWebInfLib() != null)
        {
            for (Resource r : webApp.getWebInfLib())
            {
                PathWatcher.Config config = new PathWatcher.Config(r.getFile().toPath());
                config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                scanner.watch(config);
            }
        }
    }

    /**
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#restartWebApp(boolean)
     */
    @Override
    public void restartWebApp(boolean reconfigureScanner) throws Exception
    {
        getLog().info("restarting " + webApp);
        getLog().debug("Stopping webapp ...");
        stopScanner();
        webApp.stop();

        getLog().debug("Reconfiguring webapp ...");

        checkPomConfiguration();
        configureWebApplication();

        // check if we need to reconfigure the scanner,
        // which is if the pom changes
        if (reconfigureScanner)
        {
            getLog().info("Reconfiguring scanner after change to pom.xml ...");
            scanner.reset();
            configureScanner();
        }

        getLog().debug("Restarting webapp ...");
        webApp.start();
        startScanner();
        getLog().info("Restart completed at " + new Date().toString());
    }

    /**
     *
     */
    protected String getJavaBin()
    {
        String[] javaexes = new String[]
            {"java", "java.exe"};

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir, fileSeparators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile())
            {
                return javabin.getAbsolutePath();
            }
        }

        return "java";
    }

    public static String fileSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == '/') || (c == '\\'))
            {
                ret.append(File.separatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }
}
