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

package org.eclipse.jetty.maven;

import java.io.File;

import org.apache.maven.plugins.annotations.Parameter;

/**
 * Base class for all goals that operate on unassembled webapps.
 *
 */
public abstract class AbstractUnassembledWebAppMojo extends AbstractWebAppMojo
{
    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter (defaultValue = "${project.baseDir}/src/main/webapp/WEB-INF/web.xml")
    protected File webXml;
    
    /**
     * The directory containing generated test classes.
     * 
     */
    @Parameter (defaultValue = "${project.build.testOutputDirectory}", required = true)
    protected File testClassesDirectory;
    
    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     */
    @Parameter
    protected ScanPattern scanTestClassesPattern;

    /**
     * The directory containing generated classes.
     */
    @Parameter (defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;
    

    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     */
    @Parameter
    protected ScanPattern scanClassesPattern;
    

    /**
     * Default root directory for all html/jsp etc files.
     * Used to initialize webApp.setBaseResource().
     */
    @Parameter (defaultValue = "${project.basedir}/src/main/webapp", readonly = true)
    protected File webAppSourceDirectory;

}
