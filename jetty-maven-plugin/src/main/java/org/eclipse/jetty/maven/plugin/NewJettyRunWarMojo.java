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

import java.io.Console;
import java.io.File;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;

/**
* <p>
*  This goal is used to assemble your webapp into a war and automatically deploy it to Jetty.
*  </p>
*  <p>
*  Once invoked, the plugin runs continuously and can be configured to scan for changes in the project and to the
*  war file and automatically perform a hot redeploy when necessary. 
*  </p>
*  <p>
*  You may also specify the location of a jetty.xml file whose contents will be applied before any plugin configuration.
*  </p>
*  Runs jetty on a war file
*/
@Mojo( name = "run-war", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute(phase = LifecyclePhase.PACKAGE)
public class NewJettyRunWarMojo extends AbstractWebAppMojo
{
    
    /**
     * The location of the war file.
     *
     */
    @Parameter(defaultValue="${project.build.directory}/${project.build.finalName}.war", required = true)
    private File war;
    
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
    
    
    /**
     * Do not re-process the war overlays, this will already
     * have been done by the build phase of this mojo.
     */
    @Override
    public List<Overlay> getOverlays() throws Exception
    {
       return null;
    }

    
    
    @Override
    public void configureWebApp() throws Exception
    {
        super.configureWebApp();
        webApp.setWar(war.getCanonicalPath());
    }



    /* (non-Javadoc)
     *
     */
    @Override
    public void startJettyEmbedded() throws MojoExecutionException
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

    /* (non-Javadoc)
     *
     */
    @Override
    public void startJettyForked() throws MojoExecutionException
    {
        // TODO Auto-generated method stub

    }

    /* (non-Javadoc)
     *
     */
    @Override
    public void startJettyDistro() throws MojoExecutionException
    {
        // TODO Auto-generated method stub

    }

    public void configureScanner() throws MojoExecutionException
    {
        scanner.watch(project.getFile().toPath());
        scanner.watch(war.toPath());

        scanner.addListener(new PathWatcher.EventListListener()
        {

            @Override
            public void onPathWatchEvents(List<PathWatchEvent> events)
            {
                try
                {
                    boolean reconfigure = false;
                    for (PathWatchEvent e:events)
                    {
                        if (e.getPath().equals(project.getFile().toPath()))
                        {
                            reconfigure = true;
                            break;
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

    public void restartWebApp(boolean reconfigureScanner) throws Exception 
    {
        getLog().info("Restarting webapp ...");
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
            warArtifacts = null; //?????????? TODO
            configureScanner();
        }

        getLog().debug("Restarting webapp ...");
        webApp.start();
        scanner.start();
        getLog().info("Restart completed.");
    }
    
}
