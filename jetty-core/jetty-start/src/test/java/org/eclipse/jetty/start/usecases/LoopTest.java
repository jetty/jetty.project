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

package org.eclipse.jetty.start.usecases;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class LoopTest extends AbstractUseCase
{
    @Test
    public void testLoopTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));

        // Create loop
        // richard -> harry -> tom -> richard

        Files.write(baseDir.resolve("modules/branch.mod"),
            Arrays.asList(
                "[provides]",
                "branch"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/richard.mod"),
            Arrays.asList(
                "[depends]",
                "harry"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/harry.mod"),
            Arrays.asList(
                "[depends]",
                "tom"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/other.mod"),
            Arrays.asList(
                "[provides]",
                "branch"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/root.mod"),
            Arrays.asList(
                "[depends]",
                "branch"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/tom.mod"),
            Arrays.asList(
                "[depends]",
                "richard"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=root"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--create-startd",
            "--add-module=tom"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Check Exceptions
        assertThat(results.exception.toString(), containsString("CyclicException"));
        assertThat(results.exception.toString(), containsString("cyclic"));
    }

    @Test
    public void testDynamicLoopTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules/dynamic"));
        FS.ensureDirExists(baseDir.resolve("modules"));

        // Create loop
        // richard -> dynamic/harry -> tom -> richard

        Files.write(baseDir.resolve("modules/branch.mod"),
            Arrays.asList(
                "[provides]",
                "branch"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/richard.mod"),
            Arrays.asList(
                "[depends]",
                "dynamic/harry"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dynamic/harry.mod"),
            Arrays.asList(
                "[depends]",
                "tom"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/other.mod"),
            Arrays.asList(
                "[provides]",
                "branch"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/root.mod"),
            Arrays.asList(
                "[depends]",
                "branch"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/tom.mod"),
            Arrays.asList(
                "[depends]",
                "richard"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=root"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--create-startd",
            "--add-module=tom"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Check Exceptions
        assertThat(results.exception.toString(), containsString("CyclicException"));
        assertThat(results.exception.toString(), containsString("cyclic"));
    }
}
