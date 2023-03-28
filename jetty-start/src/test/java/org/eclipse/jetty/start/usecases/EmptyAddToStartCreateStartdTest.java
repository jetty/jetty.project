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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

public class EmptyAddToStartCreateStartdTest extends AbstractUseCase
{
    @Test
    public void testEmptyAddToStartCreateStartdTest() throws Exception
    {
        setupStandardHomeDir();

        FS.touch(baseDir.resolve("unrelated.txt"));

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--add-module=extra",
            "--create-startd",
            "--add-module=optional"
        );
        ExecResults prepareResults = exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/optional.xml",
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml",
            "${jetty.home}/etc/extra.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/optional.jar",
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar",
            "${jetty.home}/lib/extra/extra0.jar",
            "${jetty.home}/lib/extra/extra1.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("extra.prop=value0");
        expectedProperties.add("main.prop=value0");
        expectedProperties.add("optional.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertDirExists("Required Directory: maindir/", results.baseHome.getPath("maindir/"));
        PathAssert.assertFileExists("Required File: start.d/extra.ini", results.baseHome.getPath("start.d/extra.ini"));
        PathAssert.assertFileExists("Required File: start.d/optional.ini", results.baseHome.getPath("start.d/optional.ini"));

        // === Validate Specific Lines Exist in Output
        assertOutputContainsRegex(prepareResults.output, "mkdir \\$\\{jetty.base\\}[\\\\/]maindir");
    }
}
