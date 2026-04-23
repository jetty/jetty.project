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

public class BasehomeWithfilesTest extends AbstractUseCase
{
    @Test
    public void testBasehomeWithfilesTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("modules/withfiles"));
        FS.ensureDirExists(baseDir.resolve("modules/withfiles/four"));
        FS.ensureDirExists(baseDir.resolve("modules/withfiles/four/sub"));
        FS.ensureDirExists(baseDir.resolve("modules/withfiles/four/sub/dir"));

        Files.writeString(baseDir.resolve("modules/withfiles.mod"),
            """
            [files]
            basehome:modules/withfiles/test.txt|one/renamed.txt
            basehome:modules/withfiles/test.txt|two/
            three/
            basehome:modules/withfiles/test.txt|three
            basehome:modules/withfiles
            basehome:modules/withfiles/four/|five/
            six/
            basehome:modules/withfiles/four/sub|six
            """, UTF_8);
        FS.touch(baseDir.resolve("modules/withfiles/four/sub/dir/test.txt"));
        FS.touch(baseDir.resolve("modules/withfiles/four/test.txt"));
        FS.touch(baseDir.resolve("modules/withfiles/test.txt"));

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--create-startd",
            "--add-modules=withfiles"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Downloaded Files
        List<String> expectedDownloads = List.of(
            "basehome:modules/withfiles/test.txt|one/renamed.txt",
            "basehome:modules/withfiles/test.txt|two/",
            "basehome:modules/withfiles/test.txt|three",
            "basehome:modules/withfiles|null",
            "basehome:modules/withfiles/four/|five/",
            "basehome:modules/withfiles/four/sub|six"
        );
        List<String> actualDownloads = results.getDownloads();
        assertThat("Downloads", actualDownloads, containsInAnyOrder(expectedDownloads.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        assertThat("Required File: test.txt", results.baseHome.getPath("test.txt"), isRegularFile());
        assertThat("Required File: one/renamed.txt", results.baseHome.getPath("one/renamed.txt"), isRegularFile());
        assertThat("Required File: two/test.txt", results.baseHome.getPath("two/test.txt"), isRegularFile());
        assertThat("Required File: three/test.txt", results.baseHome.getPath("three/test.txt"), isRegularFile());
        assertThat("Required File: four/sub/dir/test.txt", results.baseHome.getPath("four/sub/dir/test.txt"), isRegularFile());
        assertThat("Required File: five/sub/dir/test.txt", results.baseHome.getPath("five/sub/dir/test.txt"), isRegularFile());
        assertThat("Required File: six/sub/dir/test.txt", results.baseHome.getPath("six/sub/dir/test.txt"), isRegularFile());
    }
}
