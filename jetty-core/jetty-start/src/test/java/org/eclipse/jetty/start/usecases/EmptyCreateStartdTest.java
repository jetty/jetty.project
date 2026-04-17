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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.toolchain.test.PathMatchers.isDirectory;
import static org.eclipse.jetty.toolchain.test.PathMatchers.isRegularFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

public class EmptyCreateStartdTest extends AbstractUseCase
{
    @Test
    public void testEmptyCreateStartdTest() throws Exception
    {
        setupStandardHomeDir();

        FS.touch(baseDir.resolve("unrelated.txt"));

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--create-startd",
            "--add-modules=extra,optional"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/optional.xml"),
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml"),
            FS.separators("${jetty.home}/etc/extra.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            FS.separators("${jetty.home}/lib/optional.jar"),
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar"),
            FS.separators("${jetty.home}/lib/extra/extra0.jar"),
            FS.separators("${jetty.home}/lib/extra/extra1.jar")
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
        assertThat("Required Directory: maindir/", results.baseHome.getPath("maindir/"), isDirectory());
        assertThat("Required Directory: start.d/", results.baseHome.getPath("start.d/"), isDirectory());
        assertThat("Required File: start.d/extra.ini", results.baseHome.getPath("start.d/extra.ini"), isRegularFile());
        assertThat("Required File: start.d/optional.ini", results.baseHome.getPath("start.d/optional.ini"), isRegularFile());
    }
}
