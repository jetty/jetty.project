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

package org.eclipse.jetty.maven.plugin;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.util.Scanner;

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

}
