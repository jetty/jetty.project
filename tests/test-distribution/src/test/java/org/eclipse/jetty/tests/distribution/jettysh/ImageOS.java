//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution.jettysh;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder;

public abstract class ImageOS extends ImageFromDSL
{
    public static final String REGISTRY = "registry.jetty.org";
    public static final String REPOSITORY = REGISTRY + "/jetty-sh";

    private Path jettyHomePath;

    public ImageOS(String osid, Consumer<DockerfileBuilder> builderConsumer)
    {
        super(REPOSITORY + ":" + osid, builderConsumer);
    }

    protected File getJettyHomeDir()
    {
        if (jettyHomePath == null)
        {
            String jettyVersion = System.getProperty("jettyVersion");
            try
            {
                JettyHomeTester homeTester = JettyHomeTester.Builder.newInstance()
                    .jettyVersion(jettyVersion)
                    .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                    .build();
                jettyHomePath = homeTester.getJettyHome();
            }
            catch (Exception e)
            {
                throw new RuntimeException("Unable to get unpacked JETTY_HOME dir", e);
            }
        }
        return jettyHomePath.toFile();
    }
}
