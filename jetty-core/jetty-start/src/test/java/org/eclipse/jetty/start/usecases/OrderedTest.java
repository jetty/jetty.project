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
        Files.writeString(baseDir.resolve("modules/alternateA.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateA.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/alternateB.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateB.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/convenience.mod"),
            """
            [depends]
            replacement
            something-else
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dependent.mod"),
            """
            [depends]
            alternate
            [xml]
            etc/dependent.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/original.mod"),
            """
            [ini]
            impl=original
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/replacement.mod"),
            """
            [provides]
            original
            [ini]
            impl=replacement
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/something-else.mod"),
            """
            [depends]
            original
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=alternateA,dependent"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.base}/etc/alternateA.xml"),
            FS.separators("${jetty.base}/etc/dependent.xml")
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
        Files.writeString(baseDir.resolve("modules/alternateA.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateA.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/alternateB.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateB.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/convenience.mod"),
            """
            [depends]
            replacement
            something-else
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dependent.mod"),
            """
            [depends]
            alternate
            [xml]
            etc/dependent.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/original.mod"),
            """
            [ini]
            impl=original
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/replacement.mod"),
            """
            [provides]
            original
            [ini]
            impl=replacement
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/something-else.mod"),
            """
            [depends]
            original
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=dependent,alternateA"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.base}/etc/alternateA.xml"),
            FS.separators("${jetty.base}/etc/dependent.xml")
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
        Files.writeString(baseDir.resolve("modules/alternateA.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateA.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/alternateB.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateB.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/convenience.mod"),
            """
            [depends]
            replacement
            something-else
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dependent.mod"),
            """
            [depends]
            alternate
            [xml]
            etc/dependent.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/original.mod"),
            """
            [ini]
            impl=original
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/replacement.mod"),
            """
            [provides]
            original
            [ini]
            impl=replacement
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/something-else.mod"),
            """
            [depends]
            original
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=dependent"
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
        Files.writeString(baseDir.resolve("modules/alternateA.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateA.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/alternateB.mod"),
            """
            [provides]
            alternate
            [xml]
            etc/alternateB.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/convenience.mod"),
            """
            [depends]
            replacement
            something-else
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dependent.mod"),
            """
            [depends]
            alternate
            [xml]
            etc/dependent.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/original.mod"),
            """
            [ini]
            impl=original
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/replacement.mod"),
            """
            [provides]
            original
            [ini]
            impl=replacement
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/something-else.mod"),
            """
            [depends]
            original
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=main,convenience"
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
        Files.writeString(baseDir.resolve("modules/abstractA.mod"),
            """
            [depend]
            dynamic/${implA}
            [ini]
            implA=implA
            [ini-template]
            implA=implA
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/abstractB.mod"),
            """
            [depend]
            dynamic/${implB}
            [provide]
            provided
            [ini]
            implB=implB
            [ini-template]
            implB=implB
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dynamic/implA.mod"),
            """
            [depend]
            provided
            [xml]
            etc/implA.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/dynamic/implB.mod"),
            """
            [xml]
            etc/implB.xml
            """, UTF_8);

        // === Prepare Jetty Base using Main
        List<String> prepareArgs = List.of(
            "--testing-mode",
            "--create-startd",
            "--add-modules=abstractB,abstractA"
        );
        exec(prepareArgs, true);

        // === Execute Main
        List<String> runArgs = Collections.emptyList();
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.base}/etc/implB.xml"),
            FS.separators("${jetty.base}/etc/implA.xml")
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
