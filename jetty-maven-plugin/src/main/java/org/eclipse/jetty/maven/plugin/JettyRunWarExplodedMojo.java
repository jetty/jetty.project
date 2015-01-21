//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jetty.util.Scanner;

/**
 * 
 *  <p>
 *  This goal is used to assemble your webapp into an exploded war and automatically deploy it to Jetty.
 *  </p>
 *  <p>
 *  Once invoked, the plugin runs continuously, and can be configured to scan for changes in the pom.xml and 
 *  to WEB-INF/web.xml, WEB-INF/classes or WEB-INF/lib and hot redeploy when a change is detected. 
 *  </p>
 *  <p>
 *  You may also specify the location of a jetty.xml file whose contents will be applied before any plugin configuration.
 *  This can be used, for example, to deploy a static webapp that is not part of your maven build. 
 *  </p>
 *
 *@goal run-exploded
 *@requiresDependencyResolution compile+runtime
 *@execute phase=package
 */
public class JettyRunWarExplodedMojo extends AbstractJettyMojo
{

    
    
    /**
     * The location of the war file.
     * 
     * @parameter expression="${project.build.directory}/${project.build.finalName}"
     * @required
     */
    private File war;

    
   
  
   
    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#execute()
     */
    public void execute () throws MojoExecutionException, MojoFailureException
    {
        super.execute();
    }
    
    

    @Override
    public void finishConfigurationBeforeStart() throws Exception
    {
        server.setStopAtShutdown(true); //as we will normally be stopped with a cntrl-c, ensure server stopped 
        super.finishConfigurationBeforeStart();
    }

    
    /**
     * 
     * @see AbstractJettyMojo#checkPomConfiguration()
     */
    public void checkPomConfiguration() throws MojoExecutionException
    {
        return;
    }

    
    
    
    /**
     * @see AbstractJettyMojo#configureScanner()
     */
    public void configureScanner() throws MojoExecutionException
    {
        scanList = new ArrayList<File>();
        scanList.add(project.getFile());
        File webInfDir = new File(war,"WEB-INF");
        scanList.add(new File(webInfDir, "web.xml"));
        File jettyWebXmlFile = findJettyWebXmlFile(webInfDir);
        if (jettyWebXmlFile != null)
            scanList.add(jettyWebXmlFile);
        File jettyEnvXmlFile = new File(webInfDir, "jetty-env.xml");
        if (jettyEnvXmlFile.exists())
            scanList.add(jettyEnvXmlFile);
        scanList.add(new File(webInfDir, "classes"));
        scanList.add(new File(webInfDir, "lib"));

        scannerListeners = new ArrayList<Scanner.BulkListener>();
        scannerListeners.add(new Scanner.BulkListener()
        {
            public void filesChanged(List changes)
            {
                try
                {
                    boolean reconfigure = changes.contains(project.getFile().getCanonicalPath());
                    restartWebApp(reconfigure);
                }
                catch (Exception e)
                {
                    getLog().error("Error reconfiguring/restarting webapp after change in watched files",e);
                }
            }
        });
    }

    
    
    
    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#restartWebApp(boolean)
     */
    public void restartWebApp(boolean reconfigureScanner) throws Exception 
    {
        getLog().info("Restarting webapp");
        getLog().debug("Stopping webapp ...");
        webApp.stop();
        getLog().debug("Reconfiguring webapp ...");

        checkPomConfiguration();

        // check if we need to reconfigure the scanner,
        // which is if the pom changes
        if (reconfigureScanner)
        {
            getLog().info("Reconfiguring scanner after change to pom.xml ...");
            scanList.clear();
            scanList.add(project.getFile());
            File webInfDir = new File(war,"WEB-INF");
            scanList.add(new File(webInfDir, "web.xml"));
            File jettyWebXmlFile = findJettyWebXmlFile(webInfDir);
            if (jettyWebXmlFile != null)
                scanList.add(jettyWebXmlFile);
            File jettyEnvXmlFile = new File(webInfDir, "jetty-env.xml");
            if (jettyEnvXmlFile.exists())
                scanList.add(jettyEnvXmlFile);
            scanList.add(new File(webInfDir, "classes"));
            scanList.add(new File(webInfDir, "lib"));
            scanner.setScanDirs(scanList);
        }

        getLog().debug("Restarting webapp ...");
        webApp.start();
        getLog().info("Restart completed.");
    }

   

    
    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureWebApplication()
     */
    public void configureWebApplication () throws Exception
    {
        super.configureWebApplication();        
        webApp.setWar(war.getCanonicalPath());
    }
}
