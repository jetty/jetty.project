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

/**
 *  <p>
 *  This goal is similar to the jetty:run goal in that it it starts jetty on an unassembled webapp, 
 *  EXCEPT that it is designed to be bound to an execution inside your pom. Thus, this goal does NOT
 *  run a parallel build cycle, so you must be careful to ensure that you bind it to a phase in 
 *  which all necessary generated files and classes for the webapp have been created.
 *  </p>
 * <p>
 * This goal will NOT scan for changes in either the webapp project or any scanTargets or scanTargetPatterns.
 * </p>
 * <p>
 *  You can configure this goal to run your webapp either in-process with maven, or forked into a new process, or deployed into a
 *  jetty distribution.
 *  </p>
 */
@Mojo(name = "start", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.VALIDATE)
public class JettyStartMojo extends AbstractUnassembledWebAppMojo
{

    /**
     *  Starts the webapp - without first compiling the classes -
     * in the same process as maven.
     */
    @Override
    public void startJettyEmbedded() throws MojoExecutionException
    {
        try
        {
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
            jetty.setWaitForChild(false); //we never wait for child to finish
            jetty.setMaxChildStartChecks(maxChildStartChecks);
            jetty.setMaxChildStartCheckMs(maxChildStartCheckMs);
            jetty.setJettyOutputFile(getJettyOutputFile("jetty-start.out"));
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
            jetty.setWaitForChild(false); //never wait for child to finish
            jetty.setMaxChildStartChecks(maxChildStartChecks);
            jetty.setMaxChildStartCheckMs(maxChildStartCheckMs);
            jetty.setJettyOutputFile(getJettyOutputFile("jetty-start.out"));
            jetty.start(); //forks a jetty distro
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error starting jetty", e);
        }
    }
}
