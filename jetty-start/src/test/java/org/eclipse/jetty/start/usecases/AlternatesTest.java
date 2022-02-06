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

public class AlternatesTest extends AbstractUseCase
{
    @Test
    public void testAlternate0Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.touch(baseDir.resolve("etc/d.xml"));
        FS.touch(baseDir.resolve("etc/ndb.xml"));
        Files.write(baseDir.resolve("modules/alternate.mod"),
            Arrays.asList(
                "[provides]",
                "default",
                "[ini]",
                "default.option=alternate"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/default.mod"),
            Arrays.asList(
                "[xml]",
                "etc/d.xml",
                "[ini]",
                "default.option=default"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionA.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[optional]",
                "default",
                "[ini]",
                "noDft.option=A"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionB.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[depend]",
                "default",
                "[xml]",
                "etc/ndb.xml",
                "[ini]",
                "noDft.option=B"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=noDftOptionA"
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
        expectedProperties.add("noDft.option=A");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testAlternate1Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.touch(baseDir.resolve("etc/d.xml"));
        FS.touch(baseDir.resolve("etc/ndb.xml"));
        Files.write(baseDir.resolve("modules/alternate.mod"),
            Arrays.asList(
                "[provides]",
                "default",
                "[ini]",
                "default.option=alternate"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/default.mod"),
            Arrays.asList(
                "[xml]",
                "etc/d.xml",
                "[ini]",
                "default.option=default"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionA.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[optional]",
                "default",
                "[ini]",
                "noDft.option=A"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionB.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[depend]",
                "default",
                "[xml]",
                "etc/ndb.xml",
                "[ini]",
                "noDft.option=B"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=noDftOptionB"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml",
            "${jetty.base}/etc/d.xml",
            "${jetty.base}/etc/ndb.xml"
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
        expectedProperties.add("default.option=default");
        expectedProperties.add("noDft.option=B");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testAlternate2Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.touch(baseDir.resolve("etc/d.xml"));
        FS.touch(baseDir.resolve("etc/ndb.xml"));
        Files.write(baseDir.resolve("modules/alternate.mod"),
            Arrays.asList(
                "[provides]",
                "default",
                "[ini]",
                "default.option=alternate"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/default.mod"),
            Arrays.asList(
                "[xml]",
                "etc/d.xml",
                "[ini]",
                "default.option=default"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionA.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[optional]",
                "default",
                "[ini]",
                "noDft.option=A"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionB.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[depend]",
                "default",
                "[xml]",
                "etc/ndb.xml",
                "[ini]",
                "noDft.option=B"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=alternate,noDftOptionB"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml",
            "${jetty.base}/etc/ndb.xml"
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
        expectedProperties.add("default.option=alternate");
        expectedProperties.add("noDft.option=B");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testAlternate3Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.touch(baseDir.resolve("etc/d.xml"));
        FS.touch(baseDir.resolve("etc/ndb.xml"));
        Files.write(baseDir.resolve("modules/alternate.mod"),
            Arrays.asList(
                "[provides]",
                "default",
                "[ini]",
                "default.option=alternate"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/default.mod"),
            Arrays.asList(
                "[xml]",
                "etc/d.xml",
                "[ini]",
                "default.option=default"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionA.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[optional]",
                "default",
                "[ini]",
                "noDft.option=A"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionB.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[depend]",
                "default",
                "[xml]",
                "etc/ndb.xml",
                "[ini]",
                "noDft.option=B"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=alternate,default"
        );
        ExecResults results = exec(runArgs, false);

        // === Check Exceptions
        assertThat(results.exception.toString(), containsString("UsageException"));
        assertThat(results.exception.toString(), containsString("default, which is already provided by alternate"));
    }

    @Test
    public void testAlternate4Test() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.touch(baseDir.resolve("etc/d.xml"));
        FS.touch(baseDir.resolve("etc/ndb.xml"));
        Files.write(baseDir.resolve("modules/alternate.mod"),
            Arrays.asList(
                "[provides]",
                "default",
                "[ini]",
                "default.option=alternate"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/default.mod"),
            Arrays.asList(
                "[xml]",
                "etc/d.xml",
                "[ini]",
                "default.option=default"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionA.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[optional]",
                "default",
                "[ini]",
                "noDft.option=A"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("modules/noDftOptionB.mod"),
            Arrays.asList(
                "[provides]",
                "noDft",
                "[depend]",
                "default",
                "[xml]",
                "etc/ndb.xml",
                "[ini]",
                "noDft.option=B"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main"
            ),
            StandardCharsets.UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = Arrays.asList(
            "--testing-mode",
            "--add-module=noDftOptionB"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "--module=alternate"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml",
            "${jetty.base}/etc/ndb.xml"
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
        expectedProperties.add("default.option=alternate");
        expectedProperties.add("noDft.option=B");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }
}
