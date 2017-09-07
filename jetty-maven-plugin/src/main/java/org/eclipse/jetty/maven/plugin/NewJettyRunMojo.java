//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.Console;
import java.io.File;
import java.util.Date;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * NewJettyRunMojo
 * 
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
 *  Alternatively, you can configure the plugin to wait for an %lt;enter&gt; at the command line to manually control redeployment.
 */
@Mojo (name="newrun", requiresDependencyResolution = ResolutionScope.TEST)
@Execute (phase = LifecyclePhase.TEST_COMPILE)
public class NewJettyRunMojo extends AbstractWebAppMojo
{

    
    //Start of parameters only valid for runType=inprocess  
    /**
     * The interval in seconds to pause before checking if changes
     * have occurred and re-deploying as necessary. A value 
     * of 0 indicates no re-deployment will be done. In that case, you
     * can force redeployment by typing a linefeed character at the command line.
     */
    @Parameter(defaultValue="0", property="jetty.scan", required=true)
    protected int scan; 
    
    /**
     * Scanner to check for files changes to cause redeploy
     */
    protected PathWatcher scanner;




    public static class ConsoleReader implements Runnable
    {
        
        public interface Listener extends EventListener
        {
            public void consoleEvent (String line);
        }

        public Set<Listener> listeners = new HashSet<>();
        
        public void addListener(Listener listener)
        {
            listeners.add(listener);
        }
        
        public void removeListener(Listener listener)
        {
            listeners.remove(listener);
        }
        
        public void run()
        {
            Console console = System.console();
            if (console == null)
                return;

            String line ="";
            while (true && line != null)
            {
                line = console.readLine("Hit <enter> to redeploy:");
                if (line != null)
                    signalEvent(line);
            }
        }
        
        
        public void signalEvent(String line)
        {
            for (Listener l:listeners)
                l.consoleEvent(line);
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        super.execute();
    }





    @Override
    public void startJettyInProcess() throws MojoExecutionException
    {
        try
        {
            //start jetty
            JettyEmbedder jetty = newJettyEmbedder();        
            jetty.setExitVm(true);
            jetty.setStopAtShutdown(true);
            jetty.start();

            // start scanning for changes, or wait for linefeed on stdin
            if (scan > 0)
            {
                scanner = new PathWatcher();
                configureScanner ();
                scanner.setNotifyExistingOnStart(false);
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
            jetty.join();
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
            JettyForker jetty = newJettyForker();
            jetty.setWaitForChild(true); //we run at the command line, echo child output and wait for it
            jetty.start(); //forks jetty instance
            
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }





    @Override
    public void startJettyDistro() throws MojoExecutionException
    {
        try
        {
            JettyDistroForker jetty = newJettyDistroForker();
            jetty.setWaitForChild(true); //we always run at the command line, echo child output and wait for it
            jetty.start(); //forks a jetty distro
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }





    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureScanner()
     */
    public void configureScanner ()
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
                        for (PathWatchEvent e:events)
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
                    getLog().error("Error reconfiguring/restarting webapp after change in watched files",e);
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
        
        File jettyWebXmlFile = findJettyWebXmlFile(new File(webAppSourceDirectory,"WEB-INF"));
        if (jettyWebXmlFile != null)
        {
            scanner.watch(jettyWebXmlFile.toPath());
        }
        
        //make sure each of the war artifacts is added to the scanner
        for (Artifact a:getWarArtifacts())
        {
            scanner.watch(a.getFile().toPath());
        }
        
        //handle the explicit extra scan targets
        if (scanTargets != null)
        {
            for (File f:scanTargets)
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
            for (ScanTargetPattern p:scanTargetPatterns)
            {
                PathWatcher.Config config = new PathWatcher.Config(p.getDirectory().toPath());
                config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                for (String pattern:p.getExcludes())
                    config.addExcludeGlobRelative(pattern);
                for (String pattern:p.getIncludes())
                    config.addIncludeGlobRelative(pattern);
                scanner.watch(config);
            }
        }
      

        scanner.watch(project.getFile().toPath());

        if (webApp.getTestClasses() != null && webApp.getTestClasses().exists())
        {
            PathWatcher.Config config = new PathWatcher.Config(webApp.getTestClasses().toPath());
            config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);           
            if (scanTestClassesPattern != null)
            {
                for (String p:scanTestClassesPattern.getExcludes())
                    config.addExcludeGlobRelative(p);
                for (String p:scanTestClassesPattern.getIncludes())
                    config.addIncludeGlobRelative(p);
            }
            scanner.watch(config);
        }
        
        if (webApp.getClasses() != null && webApp.getClasses().exists())
        {
            PathWatcher.Config config = new PathWatcher.Config(webApp.getClasses().toPath());
            config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
            if (scanClassesPattern != null)
            {
                for (String p:scanClassesPattern.getExcludes())
                    config.addExcludeGlobRelative(p);

                for (String p:scanClassesPattern.getIncludes())
                    config.addIncludeGlobRelative(p);

            }
            scanner.watch(config);
        }

        if (webApp.getWebInfLib() != null)
        {
            for (File f:webApp.getWebInfLib())
            {
                PathWatcher.Config config = new PathWatcher.Config(f.toPath());
                config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
                scanner.watch(config);
            }
        }
    }
    
 

    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#restartWebApp(boolean)
     */
    public void restartWebApp(boolean reconfigureScanner) throws Exception 
    {
        getLog().info("Restarting "+webApp);
        getLog().debug("Stopping webapp ...");
        if (scanner != null)
            scanner.stop();
        webApp.stop();

        getLog().debug("Reconfiguring webapp ...");
 
        verifyPomConfiguration();
        configureWebApp();

        // check if we need to reconfigure the scanner,
        // which is if the pom changes
        if (reconfigureScanner)
        {
            getLog().info("Reconfiguring scanner after change to pom.xml ...");
            scanner.reset();
            warArtifacts = null;
            configureScanner();
        }

        getLog().debug("Restarting webapp ...");
        webApp.start();
        scanner.start();
        getLog().info("Restart completed at "+new Date().toString());
    }
}
