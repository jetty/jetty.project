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

        Files.writeString(baseDir.resolve("modules/branch.mod"),
            """
            [provides]
            branch
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/richard.mod"),
            """
            [depends]
            harry
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/harry.mod"),
            """
            [depends]
            tom
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/other.mod"),
            """
            [provides]
            branch
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/root.mod"),
            """
            [depends]
            branch
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/tom.mod"),
            """
            [depends]
            richard
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=root
            """, UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--create-startd",
            "--add-modules=tom"
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

        Files.writeString(baseDir.resolve("modules/branch.mod"),
            """
            [provides]
            branch
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/richard.mod"),
            """
            [depends]
            dynamic/harry
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dynamic/harry.mod"),
            """
            [depends]
            tom
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/other.mod"),
            """
            [provides]
            branch
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/root.mod"),
            """
            [depends]
            branch
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/tom.mod"),
            """
            [depends]
            richard
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=root
            """, UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--create-startd",
            "--add-modules=tom"
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
