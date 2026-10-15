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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class UriFileInitializerTest
{
    public WorkDir workDir;
    public Path testdir;

    private BaseHome baseHome;

    @BeforeEach
    public void setupBaseHome() throws IOException
    {
        testdir = workDir.getEmptyPathDir();
        Path homeDir = testdir.resolve("home");
        Path baseDir = testdir.resolve("base");

        FS.ensureDirExists(homeDir);
        FS.ensureDirExists(baseDir);

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));
        config.add(new JettyBaseConfigSource(baseDir));

        this.baseHome = new BaseHome(config);
    }

    @Test
    @Tag("external")
    public void testExists() throws Exception
    {
        UriFileInitializer initializer = new UriFileInitializer(new StartArgs(baseHome), baseHome);
        URI uri = URI.create("https://jetty.org/_/img/favicon.ico");
        assertThat("exists", initializer.exists(uri), is(true));
    }
}
