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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.Module;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

public class InheritedEnvironmentTest extends AbstractUseCase
{
    @Test
    public void testInheritOneEnvironment() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("lib"));

        Files.writeString(baseDir.resolve("etc/ee-common.xml"),
            """
            <!-- Common EE XML  -->
            """, UTF_8);

        FS.touch(baseDir.resolve("lib/ee-common.jar"));

        Files.writeString(baseDir.resolve("modules/ee-common.mod"),
            """
            [description]
            Common EE module
            
            [environment]
            <inherit>
            
            [depend]
            main
            
            [lib]
            lib/ee-common.jar
            
            [xml]
            etc/ee-common.xml
            """, UTF_8);

        Files.writeString(baseDir.resolve("etc/eeX-servlet.xml"), """
            <!-- eeX Servlet XML -->
            """, UTF_8);

        FS.touch(baseDir.resolve("lib/eeX-servlet.jar"));

        Files.writeString(baseDir.resolve("modules/eeX-servlet.mod"),
            """
            [description]
            EE specific module
            
            [environment]
            eeX
            
            [depend]
            ee-common
            
            [lib]
            lib/eeX-servlet.jar
            
            [xml]
            etc/eeX-servlet.xml
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--module=eeX-servlet"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml")
        );
        List<String> actualXmls = results.getXmls(Module.ENVIRONMENT_JETTY);
        assertThat("Jetty XML Resolution Order", actualXmls, ordered(expectedXmls));

        expectedXmls = List.of(
            FS.separators("${jetty.base}/etc/ee-common.xml"),
            FS.separators("${jetty.base}/etc/eeX-servlet.xml")
        );
        actualXmls = results.getXmls("eeX");
        assertThat("eeX XML Resolution Order", actualXmls, ordered(expectedXmls));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar")
        );
        List<String> actualLibs = results.getLibs(Module.ENVIRONMENT_JETTY);
        assertThat("Jetty Libs", actualLibs, ordered(expectedLibs));

        expectedLibs = List.of(
            FS.separators("${jetty.base}/lib/ee-common.jar"),
            FS.separators("${jetty.base}/lib/eeX-servlet.jar")
        );
        actualLibs = results.getLibs("eeX");
        assertThat("eeX Libs", actualLibs, ordered(expectedLibs));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testInheritTwoEnvironments() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("lib"));

        Files.writeString(baseDir.resolve("etc/ee-common.xml"),
            """
            <!-- Common EE XML  -->
            """, UTF_8);

        FS.touch(baseDir.resolve("lib/ee-common.jar"));

        Files.writeString(baseDir.resolve("modules/ee-common.mod"),
            """
            [description]
            Common EE module
            
            [environment]
            <inherit>
            
            [depend]
            main
            
            [lib]
            lib/ee-common.jar
            
            [xml]
            etc/ee-common.xml
            """, UTF_8);

        Files.writeString(baseDir.resolve("etc/eeX-servlet.xml"), """
            <!-- eeX Servlet XML -->
            """, UTF_8);

        FS.touch(baseDir.resolve("lib/eeX-servlet.jar"));

        Files.writeString(baseDir.resolve("modules/eeX-servlet.mod"),
            """
            [description]
            EEZ specific module
            
            [environment]
            eeX
            
            [depend]
            ee-common
            
            [lib]
            lib/eeX-servlet.jar
            
            [xml]
            etc/eeX-servlet.xml
            
            [ini]
            servlet.env=eeX
            """, UTF_8);

        Files.writeString(baseDir.resolve("etc/eeZ-servlet.xml"), """
            <!-- eeZ Servlet XML -->
            """, UTF_8);

        FS.touch(baseDir.resolve("lib/eeZ-servlet.jar"));

        Files.writeString(baseDir.resolve("modules/eeZ-servlet.mod"),
            """
            [description]
            EEZ specific module
            
            [environment]
            eeZ
            
            [depend]
            ee-common
            
            [lib]
            lib/eeZ-servlet.jar
            
            [xml]
            etc/eeZ-servlet.xml
            
            [ini]
            servlet.env=eeZ
            """, UTF_8);

        FS.touch(baseDir.resolve("lib/eeZ-webapp.jar"));

        Files.writeString(baseDir.resolve("modules/eeZ-webapp.mod"),
            """
            [description]
            EEZ specific module
            
            [environment]
            eeZ
            
            [depend]
            eeZ-servlet
            
            [lib]
            lib/eeZ-webapp.jar
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--module=eeX-servlet,eeZ-webapp"
        );
        ExecResults results = exec(runArgs, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml")
        );
        List<String> actualXmls = results.getXmls(Module.ENVIRONMENT_JETTY);
        assertThat("Jetty XML Resolution Order", actualXmls, ordered(expectedXmls));

        expectedXmls = List.of(
            FS.separators("${jetty.base}/etc/ee-common.xml"),
            FS.separators("${jetty.base}/etc/eeX-servlet.xml")
        );
        actualXmls = results.getXmls("eeX");
        assertThat("eeX XML Resolution Order", actualXmls, ordered(expectedXmls));

        expectedXmls = List.of(
            FS.separators("${jetty.base}/etc/ee-common.xml"),
            FS.separators("${jetty.base}/etc/eeZ-servlet.xml")
        );
        actualXmls = results.getXmls("eeZ");
        assertThat("eeZ XML Resolution Order", actualXmls, ordered(expectedXmls));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar")
        );
        List<String> actualLibs = results.getLibs(Module.ENVIRONMENT_JETTY);
        assertThat("Jetty Libs", actualLibs, ordered(expectedLibs));

        expectedLibs = List.of(
            FS.separators("${jetty.base}/lib/ee-common.jar"),
            FS.separators("${jetty.base}/lib/eeX-servlet.jar")
        );
        actualLibs = results.getLibs("eeX");
        assertThat("eeX Libs", actualLibs, ordered(expectedLibs));

        expectedLibs = List.of(
            FS.separators("${jetty.base}/lib/ee-common.jar"),
            FS.separators("${jetty.base}/lib/eeZ-servlet.jar"),
            FS.separators("${jetty.base}/lib/eeZ-webapp.jar")
        );
        actualLibs = results.getLibs("eeZ");
        assertThat("eeZ Libs", actualLibs, ordered(expectedLibs));

        // === Validate Resulting Properties
        Set<String> expectedProperties = Set.of(
            "main.prop=value0"
        );
        List<String> actualProperties = results.getProperties(Module.ENVIRONMENT_JETTY);
        assertThat("Jetty Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        expectedProperties = Set.of(
            "servlet.env=eeX"
        );
        actualProperties = results.getProperties("eeX");
        assertThat("eeX Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        expectedProperties = Set.of(
            "servlet.env=eeZ"
        );
        actualProperties = results.getProperties("eeZ");
        assertThat("eeZ Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }
}
