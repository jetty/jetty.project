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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
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
        Files.writeString(baseDir.resolve("modules/alternate.mod"),
            """
            [provides]
            default
            [ini]
            default.option=alternate
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/default.mod"),
            """
            [xml]
            etc/d.xml
            [ini]
            default.option=default
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionA.mod"),
            """
            [provides]
            noDft
            [optional]
            default
            [ini]
            noDft.option=A
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionB.mod"),
            """
            [provides]
            noDft
            [depend]
            default
            [xml]
            etc/ndb.xml
            [ini]
            noDft.option=B
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=noDftOptionA"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar")
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
        Files.writeString(baseDir.resolve("modules/alternate.mod"),
            """
            [provides]
            default
            [ini]
            default.option=alternate
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/default.mod"),
            """
            [xml]
            etc/d.xml
            [ini]
            default.option=default
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionA.mod"),
            """
            [provides]
            noDft
            [optional]
            default
            [ini]
            noDft.option=A
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionB.mod"),
            """
            [provides]
            noDft
            [depend]
            default
            [xml]
            etc/ndb.xml
            [ini]
            noDft.option=B
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=noDftOptionB"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml"),
            FS.separators("${jetty.base}/etc/d.xml"),
            FS.separators("${jetty.base}/etc/ndb.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar")
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
        Files.writeString(baseDir.resolve("modules/alternate.mod"),
            """
            [provides]
            default
            [ini]
            default.option=alternate
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/default.mod"),
            """
            [xml]
            etc/d.xml
            [ini]
            default.option=default
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionA.mod"),
            """
            [provides]
            noDft
            [optional]
            default
            [ini]
            noDft.option=A
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionB.mod"),
            """
            [provides]
            noDft
            [depend]
            default
            [xml]
            etc/ndb.xml
            [ini]
            noDft.option=B
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=alternate,noDftOptionB"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml"),
            FS.separators("${jetty.base}/etc/ndb.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar")
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
        Files.writeString(baseDir.resolve("modules/alternate.mod"),
            """
            [provides]
            default
            [ini]
            default.option=alternate
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/default.mod"),
            """
            [xml]
            etc/d.xml
            [ini]
            default.option=default
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionA.mod"),
            """
            [provides]
            noDft
            [optional]
            default
            [ini]
            noDft.option=A
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionB.mod"),
            """
            [provides]
            noDft
            [depend]
            default
            [xml]
            etc/ndb.xml
            [ini]
            noDft.option=B
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=alternate,default"
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
        Files.writeString(baseDir.resolve("modules/alternate.mod"),
            """
            [provides]
            default
            [ini]
            default.option=alternate
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/default.mod"),
            """
            [xml]
            etc/d.xml
            [ini]
            default.option=default
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionA.mod"),
            """
            [provides]
            noDft
            [optional]
            default
            [ini]
            noDft.option=A
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/noDftOptionB.mod"),
            """
            [provides]
            noDft
            [depend]
            default
            [xml]
            etc/ndb.xml
            [ini]
            noDft.option=B
            """, UTF_8);
        Files.writeString(baseDir.resolve("start.ini"),
        """
            --modules=main
            """, UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--add-modules=noDftOptionB"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=alternate"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml"),
            FS.separators("${jetty.base}/etc/ndb.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar")
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
