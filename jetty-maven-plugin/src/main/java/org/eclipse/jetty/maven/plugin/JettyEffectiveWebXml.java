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
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.util.StringUtil;

/**
 * Generate the effective web.xml for a pre-built webapp. This goal will NOT
 * first build the webapp, it must already exist.
 *
 */
@Mojo(name = "effective-web-xml", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class JettyEffectiveWebXml extends AbstractUnassembledWebAppMojo
{
    /**
     * The name of the file to generate into
     */
    @Parameter (defaultValue = "${project.build.directory}/effective-web.xml")
    protected File effectiveWebXml;
    
    @Override
    public void configureWebApp() throws Exception
    {
        //Use a nominated war file for which to generate the effective web.xml, or
        //if that is not set, try to use the details of the current project's 
        //unassembled webapp
        super.configureWebApp();
        if (StringUtil.isBlank(webApp.getWar()))
            super.configureUnassembledWebApp();
    }
    
    /**
     * Override so we can call the parent's method in a different order.
     */
    @Override
    protected void configureUnassembledWebApp() throws Exception
    {
    }

    @Override
    protected void startJettyEmbedded() throws MojoExecutionException
    {
        generate();
    }

    @Override
    protected void startJettyForked() throws MojoExecutionException
    {
        generate();
    }

    @Override
    protected void startJettyDistro() throws MojoExecutionException
    {
        generate();
    }

    private void generate() throws MojoExecutionException
    {
        try
        {
            QuickStartGenerator generator = new QuickStartGenerator(effectiveWebXml, webApp);
            generator.generate();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error generating effective web xml", e);
        }
    }
}
