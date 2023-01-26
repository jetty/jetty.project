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

import java.nio.file.Path;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.maven.AbstractWebAppMojo;
import org.eclipse.jetty.util.Scanner;

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
*  <p>
*  You can configure this goal to run your webapp either in-process with maven, or forked into a new process, or deployed into a
*  jetty distribution.
*  </p>
*/
@Mojo(name = "run-war", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute(phase = LifecyclePhase.PACKAGE)
public class JettyRunWarMojo extends AbstractWebAppMojo
{   
    /**
     * The interval in seconds to pause before checking if changes
     * have occurred and re-deploying as necessary. A value 
     * of 0 indicates no re-deployment will be done. In that case, you
     * can force redeployment by typing a linefeed character at the command line.
     */
    @Parameter(defaultValue = "0", property = "jetty.scan", required = true)
    protected int scan; 
    
    /**
     * Scanner to check for files changes to cause redeploy
     */
    protected Scanner scanner;

    protected Path war;

}
