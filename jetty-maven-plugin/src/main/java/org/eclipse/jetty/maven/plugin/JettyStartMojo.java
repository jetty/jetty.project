//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * <p>
 * This goal is similar to the jetty:run goal, EXCEPT that it is designed to be bound to an execution inside your pom, rather
 * than being run from the command line.
 * </p>
 * <p>
 * When using it, be careful to ensure that you bind it to a phase in which all necessary generated files and classes for the webapp
 * will have been created. If you run it from the command line, then also ensure that all necessary generated files and classes for
 * the webapp already exist.
 * </p>
 *
 * Runs jetty directly from a maven project from a binding to an execution in your pom
 */
@Mojo(name = "start", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.VALIDATE)
public class JettyStartMojo extends JettyRunMojo
{

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        nonBlocking = true; //ensure that starting jetty won't hold up the thread
        super.execute();
    }

    @Override
    public void finishConfigurationBeforeStart() throws Exception
    {
        super.finishConfigurationBeforeStart();
        server.setStopAtShutdown(false); //as we will normally be stopped with a cntrl-c, ensure server stopped 
    }
}
