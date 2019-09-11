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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 *  <p>
 *  This goal is similar to the jetty:run goal, EXCEPT that it is designed to be bound to an execution inside your pom, rather
 *  than being run from the command line. 
 *  </p>
 *  <p>
 *  When using it, be careful to ensure that you bind it to a phase in which all necessary generated files and classes for the webapp
 *  will have been created. If you run it from the command line, then also ensure that all necessary generated files and classes for
 *  the webapp ALREADY exist.
 *  </p>
 *
 */
@Mojo( name = "newstart", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.VALIDATE)
public class NewJettyStartMojo extends AbstractWebAppMojo
{
    @Override
    protected void configureWebApp() throws Exception
    {
        super.configureWebApp();
        super.configureUnassembledWebApp();
    }
    
    /** Starts the webapp - without first compiling the classes -
     * in the same process as maven.
     */
    @Override
    public void startJettyEmbedded() throws MojoExecutionException
    {
        try
        {
            //start jetty
            JettyEmbedder jetty = newJettyEmbedder();        
            jetty.setExitVm(false);
            jetty.setStopAtShutdown(false);
            jetty.start();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }


    /**
     * Start the webapp in a forked jetty process. Use the
     * jetty:stop goal to terminate.
     */
    @Override
    public void startJettyForked() throws MojoExecutionException
    {
        try
        {
            JettyForker jetty = newJettyForker();
            jetty.setWaitForChild(false); //we never wait for child
            jetty.setJettyOutputFile(getJettyOutputFile());
            jetty.start(); //forks jetty instance
            
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    /**
     * Start the webapp in a forked jetty distribution. Use the
     * jetty:stop goal to terminate
     */
    @Override
    public void startJettyDistro() throws MojoExecutionException
    {
        try
        {
            JettyDistroForker jetty = newJettyDistroForker();
            jetty.setWaitForChild(false); //never wait for child
            jetty.setJettyOutputFile(getJettyOutputFile());
            jetty.start(); //forks a jetty distro
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }

    protected File getJettyOutputFile () throws Exception
    {
        File outputFile = new File(target, "jetty.out");
        if (outputFile.exists())
            outputFile.delete();
        outputFile.createNewFile();
        return outputFile;
    }
}
