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

import java.io.File;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.maven.AbstractUnassembledWebAppMojo;

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

}
