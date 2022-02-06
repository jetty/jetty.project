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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

public class ParameterizedTest extends AbstractUseCase
{
    @Test
    public void testParameterizedAddToStartTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("start.d"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.write(baseDir.resolve("etc/commands.txt"),
            Arrays.asList(
                "name0=changed0",
                "name1=changed1",
                "--add-module=parameterized",
                "# ignore this"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/parameterized.mod"),
            Arrays.asList(
                "[depend]",
                "main",
                "[ini]",
                "name=value",
                "name0?=default",
                "name2?=two",
                "[ini-template]",
                "name0=value0",
                "# name1=value1",
                "# name2=too"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.d/tobeupdated.ini"),
            Arrays.asList(
                "#p=v",
                "property=value",
                "#comment",
                "property0=value0",
                "#comment",
                "#property1=value1"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--create-startd",
            "other=value",
            "name=changed",
            "name0=changed0",
            "name1=changed1",
            "--add-module=parameterized"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        expectedProperties.add("name=value");
        expectedProperties.add("name0=changed0");
        expectedProperties.add("name1=changed1");
        expectedProperties.add("name2=two");
        expectedProperties.add("property=value");
        expectedProperties.add("property0=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertFileExists("Required File: start.d/parameterized.ini", results.baseHome.getPath("start.d/parameterized.ini"));
    }

    @Test
    public void testParameterizedCommandsTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("start.d"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.write(baseDir.resolve("etc/commands.txt"),
            Arrays.asList(
                "name0=changed0",
                "name1=changed1",
                "--add-module=parameterized",
                "# ignore this"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/parameterized.mod"),
            Arrays.asList(
                "[depend]",
                "main",
                "[ini]",
                "name=value",
                "name0?=default",
                "name2?=two",
                "[ini-template]",
                "name0=value0",
                "# name1=value1",
                "# name2=too"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.d/tobeupdated.ini"),
            Arrays.asList(
                "#p=v",
                "property=value",
                "#comment",
                "property0=value0",
                "#comment",
                "#property1=value1"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--create-startd",
            "other=value",
            "name=changed",
            "--commands=etc/commands.txt"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        expectedProperties.add("name=value");
        expectedProperties.add("name0=changed0");
        expectedProperties.add("name1=changed1");
        expectedProperties.add("name2=two");
        expectedProperties.add("property=value");
        expectedProperties.add("property0=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertFileExists("Required File: start.d/parameterized.ini", results.baseHome.getPath("start.d/parameterized.ini"));
    }

    @Test
    public void testParameterizedUpdateTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("start.d"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.write(baseDir.resolve("etc/commands.txt"),
            Arrays.asList(
                "name0=changed0",
                "name1=changed1",
                "--add-module=parameterized",
                "# ignore this"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/parameterized.mod"),
            Arrays.asList(
                "[depend]",
                "main",
                "[ini]",
                "name=value",
                "name0?=default",
                "name2?=two",
                "[ini-template]",
                "name0=value0",
                "# name1=value1",
                "# name2=too"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.d/tobeupdated.ini"),
            Arrays.asList(
                "#p=v",
                "property=value",
                "#comment",
                "property0=value0",
                "#comment",
                "#property1=value1"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--create-startd",
            "other=value",
            "name=changed",
            "name0=changed0",
            "name1=changed1",
            "--add-module=parameterized",
            "--update-ini",
            "property0=changed0",
            "property1=changed1",
            "name0=updated0"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        expectedProperties.add("name=value");
        expectedProperties.add("name0=updated0");
        expectedProperties.add("name1=changed1");
        expectedProperties.add("name2=two");
        expectedProperties.add("property=value");
        expectedProperties.add("property0=changed0");
        expectedProperties.add("property1=changed1");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        PathAssert.assertFileExists("Required File: start.d/parameterized.ini", results.baseHome.getPath("start.d/parameterized.ini"));
    }
}
