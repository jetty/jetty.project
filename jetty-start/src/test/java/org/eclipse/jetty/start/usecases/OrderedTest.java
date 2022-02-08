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
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;

public class OrderedTest extends AbstractUseCase
{
    @Test
    public void testOrdered0Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.touch(baseDir.resolve("etc/alternateA.xml"));
        FS.touch(baseDir.resolve("etc/alternateB.xml"));
        FS.touch(baseDir.resolve("etc/dependent.xml"));
        Files.write(baseDir.resolve("modules/alternateA.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateA.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/alternateB.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateB.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/convenience.mod"),
            Arrays.asList(
                "[depends]",
                "replacement",
                "something-else"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dependent.mod"),
            Arrays.asList(
                "[depends]",
                "alternate",
                "[xml]",
                "etc/dependent.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/original.mod"),
            Arrays.asList(
                "[ini]",
                "impl=original"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/replacement.mod"),
            Arrays.asList(
                "[provides]",
                "original",
                "[ini]",
                "impl=replacement"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/something-else.mod"),
            Arrays.asList(
                "[depends]",
                "original"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=alternateA,dependent"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.base}/etc/alternateA.xml",
            "${jetty.base}/etc/dependent.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));
    }

    @Test
    public void testOrdered1Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.touch(baseDir.resolve("etc/alternateA.xml"));
        FS.touch(baseDir.resolve("etc/alternateB.xml"));
        FS.touch(baseDir.resolve("etc/dependent.xml"));
        Files.write(baseDir.resolve("modules/alternateA.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateA.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/alternateB.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateB.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/convenience.mod"),
            Arrays.asList(
                "[depends]",
                "replacement",
                "something-else"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dependent.mod"),
            Arrays.asList(
                "[depends]",
                "alternate",
                "[xml]",
                "etc/dependent.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/original.mod"),
            Arrays.asList(
                "[ini]",
                "impl=original"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/replacement.mod"),
            Arrays.asList(
                "[provides]",
                "original",
                "[ini]",
                "impl=replacement"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/something-else.mod"),
            Arrays.asList(
                "[depends]",
                "original"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=dependent,alternateA"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.base}/etc/alternateA.xml",
            "${jetty.base}/etc/dependent.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));
    }

    @Test
    public void testOrdered2Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.touch(baseDir.resolve("etc/alternateA.xml"));
        FS.touch(baseDir.resolve("etc/alternateB.xml"));
        FS.touch(baseDir.resolve("etc/dependent.xml"));
        Files.write(baseDir.resolve("modules/alternateA.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateA.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/alternateB.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateB.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/convenience.mod"),
            Arrays.asList(
                "[depends]",
                "replacement",
                "something-else"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dependent.mod"),
            Arrays.asList(
                "[depends]",
                "alternate",
                "[xml]",
                "etc/dependent.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/original.mod"),
            Arrays.asList(
                "[ini]",
                "impl=original"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/replacement.mod"),
            Arrays.asList(
                "[provides]",
                "original",
                "[ini]",
                "impl=replacement"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/something-else.mod"),
            Arrays.asList(
                "[depends]",
                "original"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=dependent"
        );
        ExecResults results = exec(runArgs, false);

        // === Check Exceptions
        assertThat(results.exception.toString(), containsString("UsageException: Unsatisfied module dependencies"));
    }

    @Test
    public void testOrderedDefaultTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.touch(baseDir.resolve("etc/alternateA.xml"));
        FS.touch(baseDir.resolve("etc/alternateB.xml"));
        FS.touch(baseDir.resolve("etc/dependent.xml"));
        Files.write(baseDir.resolve("modules/alternateA.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateA.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/alternateB.mod"),
            Arrays.asList(
                "[provides]",
                "alternate",
                "[xml]",
                "etc/alternateB.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/convenience.mod"),
            Arrays.asList(
                "[depends]",
                "replacement",
                "something-else"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dependent.mod"),
            Arrays.asList(
                "[depends]",
                "alternate",
                "[xml]",
                "etc/dependent.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/original.mod"),
            Arrays.asList(
                "[ini]",
                "impl=original"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/replacement.mod"),
            Arrays.asList(
                "[provides]",
                "original",
                "[ini]",
                "impl=replacement"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/something-else.mod"),
            Arrays.asList(
                "[depends]",
                "original"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=main,convenience"
        );
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
        expectedProperties.add("impl=replacement");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testOrderedProvided0Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("modules/dynamic"));
        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.touch(baseDir.resolve("etc/implA.xml"));
        FS.touch(baseDir.resolve("etc/implB.xml"));
        Files.write(baseDir.resolve("modules/abstractA.mod"),
            Arrays.asList(
                "[depend]",
                "dynamic/${implA}",
                "[ini]",
                "implA=implA",
                "[ini-template]",
                "implA=implA"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/abstractB.mod"),
            Arrays.asList(
                "[depend]",
                "dynamic/${implB}",
                "[provide]",
                "provided",
                "[ini]",
                "implB=implB",
                "[ini-template]",
                "implB=implB"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dynamic/implA.mod"),
            Arrays.asList(
                "[depend]",
                "provided",
                "[xml]",
                "etc/implA.xml"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/dynamic/implB.mod"),
            Arrays.asList(
                "[xml]",
                "etc/implB.xml"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--create-startd",
            "--add-module=abstractB,abstractA"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.base}/etc/implB.xml",
            "${jetty.base}/etc/implA.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("implA=implA");
        expectedProperties.add("implB=implB");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }
}
