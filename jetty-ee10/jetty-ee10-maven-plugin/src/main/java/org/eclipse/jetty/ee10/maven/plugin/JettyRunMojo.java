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
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Date;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 *  This goal is used in-situ on a Maven project without first requiring that the project 
 *  is assembled into a war, saving time during the development cycle.
 *  <p>
 *  The plugin runs a parallel lifecycle to ensure that the "test-compile" phase has been completed before invoking Jetty. This means
 *  that you do not need to explicity execute a "mvn compile" first. It also means that a "mvn clean jetty:run" will ensure that
 *  a full fresh compile is done before invoking Jetty.
 *  <p>
 *  Once invoked, the plugin can be configured to run continuously, scanning for changes in the project and automatically performing a 
 *  hot redeploy when necessary. This allows the developer to concentrate on coding changes to the project using their IDE of choice and have those changes
 *  immediately and transparently reflected in the running web container, eliminating development time that is wasted on rebuilding, reassembling and redeploying.
 *  Alternatively, you can configure the plugin to wait for an &lt;enter&gt; at the command line to manually control redeployment.
 *  <p>
 *  You can configure this goal to run your unassembled webapp either in-process with maven, or forked into a new process, or deployed into a
 *  jetty distribution.
 */
@Mojo (name = "run", requiresDependencyResolution = ResolutionScope.TEST)
@Execute (phase = LifecyclePhase.TEST_COMPILE)
public class JettyRunMojo extends AbstractUnassembledWebAppMojo
{
    //Start of parameters only valid for deploymentType=EMBED  
    /**
     * Controls redeployment of the webapp.
     * <ol>
     * <li> -1 : means no redeployment will be done </li>
     * <li>  0 : means redeployment only occurs if you hit the ENTER key </li>
     * <li>  otherwise, the interval in seconds to pause before checking and redeploying if necessary </li>
     * </ol>
     */
    @Parameter(defaultValue = "-1", property = "jetty.scan", required = true)
    protected int scan;

    /**
     * Scanner to check for files changes to cause redeploy
     */
    protected Scanner scanner;

    /**
     * Only one of the following will be used, depending the mode
     * the mojo is started in: EMBED, FORK, EXTERNAL
     */
    protected JettyEmbedder embedder;
    protected JettyForker forker;
    protected JettyHomeForker homeForker;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        super.execute();
    }

    @Override
    public void startJettyEmbedded() throws MojoExecutionException
    {
        try
        {
            //start jetty
            embedder = newJettyEmbedder();
            embedder.setExitVm(true);
            embedder.setStopAtShutdown(true);
            embedder.start();
            startScanner();
            embedder.join();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    @Override
    public void startJettyForked() throws MojoExecutionException
    {
        try
        {
            forker = newJettyForker();
            forker.setWaitForChild(true); //we run at the command line, echo child output and wait for it
            forker.setScan(true); //have the forked child notice changes to the webapp
            //TODO is it ok to start the scanner before we start jetty?
            startScanner();
            forker.start(); //forks jetty instance 
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    @Override
    public void startJettyHome() throws MojoExecutionException
    {
        try
        {
            homeForker = newJettyHomeForker();
            homeForker.setWaitForChild(true); //we always run at the command line, echo child output and wait for it
            //TODO is it ok to start the scanner before we start jetty?
            startScanner();
            homeForker.start(); //forks a jetty distro
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    private void startScanner()
        throws Exception
    {
        if (scan < 0)
        {
            getLog().info("Automatic redeployment disabled, see 'mvn jetty:help' for more redeployment options");
            return; //no automatic or manual redeployment
        }
        
        // start scanning for changes, or wait for linefeed on stdin
        if (scan > 0)
        {
            scanner = new Scanner();
            scanner.setScanInterval(scan);
            scanner.setScanDepth(Scanner.MAX_SCAN_DEPTH); //always fully walk directory hierarchies
            scanner.setReportExistingFilesOnStartup(false);
            scanner.addListener(new Scanner.BulkListener()
            {   
                public void filesChanged(Set<String> changes)
                {
                    try
                    {
                        restartWebApp(changes.contains(project.getFile().getCanonicalPath()));
                    }
                    catch (Exception e)
                    {
                        getLog().error("Error reconfiguring/restarting webapp after change in watched files", e);
                    }
                }
            });
            configureScanner();
            getLog().info("Scan interval sec = " + scan);
            
            //unmanage scheduler so it is not stopped with the scanner
            Scheduler scheduler = scanner.getBean(Scheduler.class);
            scanner.unmanage(scheduler);
            LifeCycle.start(scheduler);
            scanner.start();
        }
        else
        {
            ConsoleReader creader = new ConsoleReader();
            creader.addListener(new ConsoleReader.Listener()
            {
                @Override
                public void consoleEvent(String line)
                {
                    try
                    {
                        restartWebApp(false);
                    }
                    catch (Exception e)
                    {
                        getLog().debug(e);
                    }
                }
            });
            Thread cthread = new Thread(creader, "ConsoleReader");
            cthread.setDaemon(true);
            cthread.start();
        }
    }

    protected void configureScanner()
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
    }

    public void gatherScannables() throws Exception
    {
        if (webApp.getDescriptor() != null)
        {
            Resource r = webApp.getResourceFactory().newResource(webApp.getDescriptor());
            scanner.addFile(r.getPath());
        }

        if (webApp.getJettyEnvXml() != null)
            scanner.addFile(new File(webApp.getJettyEnvXml()).toPath());

        if (webApp.getDefaultsDescriptor() != null)
        {
            if (!WebAppContext.WEB_DEFAULTS_XML.equals(webApp.getDefaultsDescriptor()))
                scanner.addFile(new File(webApp.getDefaultsDescriptor()).toPath());
        }

        if (webApp.getOverrideDescriptor() != null)
        {
            scanner.addFile(new File(webApp.getOverrideDescriptor()).toPath());
        }

        File jettyWebXmlFile = findJettyWebXmlFile(new File(webAppSourceDirectory, "WEB-INF"));
        if (jettyWebXmlFile != null)
        {
            scanner.addFile(jettyWebXmlFile.toPath());
        }

        //make sure each of the war artifacts is added to the scanner
        for (Artifact a:mavenProjectHelper.getWarPluginInfo().getWarArtifacts())
        {
            File f = a.getFile();
            if (a.getFile().isDirectory())
                scanner.addDirectory(f.toPath());
            else
                scanner.addFile(f.toPath());
        }

        //set up any extra files or dirs to watch
        configureScanTargetPatterns(scanner);

        scanner.addFile(project.getFile().toPath());

        if (webApp.getTestClasses() != null && webApp.getTestClasses().exists())
        {
            Path p = webApp.getTestClasses().toPath();
            IncludeExcludeSet<PathMatcher, Path> includeExcludeSet = scanner.addDirectory(p);
            if (scanTestClassesPattern != null)
            {
                for (String s : scanTestClassesPattern.getExcludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludeSet.exclude(p.getFileSystem().getPathMatcher(s));
                }
                for (String s : scanTestClassesPattern.getIncludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludeSet.include(p.getFileSystem().getPathMatcher(s));
                }
            }
        }

        if (webApp.getClasses() != null && webApp.getClasses().exists())
        {
            Path p = webApp.getClasses().toPath();
            IncludeExcludeSet<PathMatcher, Path> includeExcludes = scanner.addDirectory(p);
            if (scanClassesPattern != null)
            {
                for (String s : scanClassesPattern.getExcludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludes.exclude(p.getFileSystem().getPathMatcher(s));
                }

                for (String s : scanClassesPattern.getIncludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludes.include(p.getFileSystem().getPathMatcher(s));
                }
            }
        }

        if (webApp.getWebInfLib() != null)
        {
            for (File f : webApp.getWebInfLib())
            {
                if (f.isDirectory())
                    scanner.addDirectory(f.toPath());
                else
                    scanner.addFile(f.toPath());
            }
        }
    }

    /**
     * Stop an executing webapp and restart it after optionally
     * reconfiguring it.
     *
     * @param reconfigure if true, the scanner will
     * be reconfigured after changes to the pom. If false, only
     * the webapp will be reconfigured.
     *
     * @throws Exception
     */
    public void restartWebApp(boolean reconfigure) throws Exception
    {
        getLog().info("Restarting " + webApp);
        getLog().debug("Stopping webapp ...");
        if (scanner != null)
            scanner.stop();

        switch (deployMode)
        {
            case EMBED:
            {
                getLog().debug("Reconfiguring webapp ...");

                verifyPomConfiguration();
                // check if we need to reconfigure the scanner,
                // which is if the pom changes
                if (reconfigure)
                {
                    getLog().info("Reconfiguring scanner after change to pom.xml ...");
                    warArtifacts = null; //will be regenerated by configureWebApp
                    if (scanner != null)
                    {
                        scanner.reset();
                        configureScanner();
                    }
                }

                embedder.getWebApp().stop();
                configureWebApp();
                embedder.redeployWebApp();
                if (scanner != null)
                    scanner.start();
                getLog().info("Restart completed at " + new Date().toString());

                break;
            }
            case FORK:
            {
                verifyPomConfiguration();
                if (reconfigure)
                {
                    getLog().info("Reconfiguring scanner after change to pom.xml ...");
                    warArtifacts = null; ///TODO if the pom changes for the forked case, how would we get the forked process to stop and restart?
                    if (scanner != null)
                    {
                        scanner.reset();
                        configureScanner();
                    }
                }
                configureWebApp();
                //regenerate with new config and restart the webapp
                forker.redeployWebApp();
                //restart scanner
                if (scanner != null)
                    scanner.start();
                break;
            }
            case DISTRO:
            case HOME:
            case EXTERNAL:
            {
                if (deployMode != DeploymentMode.EXTERNAL)
                    getLog().warn(deployMode + " mode is deprecated, use mode EXTERNAL");
                verifyPomConfiguration();
                if (reconfigure)
                {
                    getLog().info("Reconfiguring scanner after change to pom.xml ...");

                    warArtifacts = null; //TODO if there are any changes to the pom, then we would have to tell the
                    //existing forked home process to stop, then rerun the configuration and then refork - too complicated??!
                    if (scanner != null)
                    {
                        scanner.reset();
                        configureScanner();
                    }
                }
                configureWebApp();
                //regenerate the webapp and redeploy it
                homeForker.redeployWebApp();
                //restart scanner
                if (scanner != null)
                    scanner.start();

                break;
            }
            default:
            {
                throw new IllegalStateException("Unrecognized run type " + deployMode);
            }
        }
    }
}
