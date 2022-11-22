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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

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
        super.configureWebApp();

        //Try to determine if we're using an unassembled webapp, or an
        //external||prebuilt webapp
        String war = webApp.getWar();
        Path path = null;
        if (war != null)
        {
            try
            {
                URL url = new URL(war);
                path = Paths.get(url.toURI());
            }
            catch (MalformedURLException e)
            {
                path = Paths.get(war);
            }
        }

        Path start = path.getName(0);
        int count = path.getNameCount();
        Path end = path.getName(count > 0 ? count - 1 : count);
        //if the war is not assembled, we must configure it
        if (start.startsWith("src") || !end.toString().endsWith(".war"))
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
    protected void startJettyHome() throws MojoExecutionException
    {
        generate();
    }

    private void generate() throws MojoExecutionException
    {
        try
        {
            QuickStartGenerator generator = new QuickStartGenerator(effectiveWebXml.toPath(), webApp);
            generator.generate();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error generating effective web xml", e);
        }
    }
}
