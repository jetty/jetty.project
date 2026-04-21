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

package org.eclipse.jetty.start.usecases;

import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.toolchain.test.PathMatchers.isRegularFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

public class Files0Test extends AbstractUseCase
{
    @Test
    public void testFiles0Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("modules/demo"));
        Files.writeString(baseDir.resolve("modules/demo.mod"),
            """
            [files]
            basehome:modules/demo/demo-config.xml|etc/demo-config.xml
            """, UTF_8);
        FS.touch(baseDir.resolve("modules/demo/demo-config.xml"));

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--create-startd",
            "--add-modules=demo"
        );
        ExecResults prepareResults = exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Downloaded Files
        List<String> expectedDownloads = List.of(
            "basehome:modules/demo/demo-config.xml|etc/demo-config.xml"
        );
        List<String> actualDownloads = results.getDownloads();
        assertThat("Downloads", actualDownloads, containsInAnyOrder(expectedDownloads.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        assertThat("Required File: etc/demo-config.xml", results.baseHome.getPath("etc/demo-config.xml"), isRegularFile());
    }
}
