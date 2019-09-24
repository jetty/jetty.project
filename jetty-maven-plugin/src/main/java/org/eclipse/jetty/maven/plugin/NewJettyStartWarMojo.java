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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>
 * This goal is used to run Jetty with any pre-assembled war.  This goal does not have 
 * to be used with a project of packaging type "war".
 * </p>
 * <p>
 * You must configure the "webApp" element with the location of either a war file or
 * an unpacked war that you wish to deploy - in either case, the webapp must be 
 * fully compiled and assembled as this goal does not do anything other than start
 * jetty with the given webapp.
 * </p>
 * <p>
 * This goal is designed to be bound to a build phase, and NOT to be run at the
 * command line. It will not block waiting for jetty to execute, but rather continue
 * execution.
 * </p>
 * <p>
 * This goal is useful e.g. for launching a web app in Jetty as a target for unit-tested
 * HTTP client components via binding to the test-integration build phase.
 * </p>
 */
@Mojo(name = "start-war", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class NewJettyStartWarMojo extends AbstractWebAppMojo
{
    protected JettyEmbedder embedder;
    protected JettyForker forker;
    protected JettyDistroForker distroForker;

    
    @Override
    public void configureWebApp() throws Exception
    {
        super.configureWebApp();
        if (StringUtil.isBlank(webApp.getWar()))
        {
            super.verifyPomConfiguration();
            configureUnassembledWebApp();
        }

        getLog().info("War = "+webApp.getWar());
    }
    
    /**
     * Start a jetty instance in process to run given war.
     */
    @Override
    public void startJettyEmbedded() throws MojoExecutionException
    {
        try
        {
            embedder = newJettyEmbedder();        
            embedder.setExitVm(false);
            embedder.setStopAtShutdown(false);
            embedder.start();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    
    /**
     * Fork a jetty instance to run the given war.
     */
    @Override
    public void startJettyForked() throws MojoExecutionException
    {
        try
        {
            forker = newJettyForker();
            forker.setWaitForChild(false); //we never wait for child
            forker.setJettyOutputFile(getJettyOutputFile("jetty-start-war.out"));
            forker.start(); //forks jetty instance
 
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    /**
     * Fork a jetty distro to run the given war.
     */
    @Override
    public void startJettyDistro() throws MojoExecutionException
    {
        try
        {
            distroForker = newJettyDistroForker();
            distroForker.setWaitForChild(false); //never wait for child
            distroForker.setJettyOutputFile(getJettyOutputFile("jetty-start-war.out"));
            distroForker.start(); //forks a jetty distro
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }
   
    /**
     *
     */
    @Override
    protected void verifyPomConfiguration() throws MojoExecutionException
    {
        //Do not verify the configuration of the webapp, as we are deploying
        //a random war instead.
    }
}
