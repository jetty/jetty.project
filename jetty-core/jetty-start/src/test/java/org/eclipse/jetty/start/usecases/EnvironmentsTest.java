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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.Environment;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class EnvironmentsTest extends AbstractUseCase
{
    @Test
    public void testTwoEnvironments() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("lib"));
        FS.ensureDirExists(baseDir.resolve("modules"));

        FS.touch(baseDir.resolve("lib/envA.jar"));
        FS.touch(baseDir.resolve("etc/envA.xml"));
        Files.write(baseDir.resolve("modules/feature-envA.mod"),
            Arrays.asList(
                "[provides]",
                "feature-envA",
                "[environment]",
                "envA",
                "[depends]",
                "main",
                "[xml]",
                "etc/envA.xml",
                "[lib]",
                "lib/envA.jar",
                "[ini]",
                "feature.option=envA"
            ),
            StandardCharsets.UTF_8);

        FS.touch(baseDir.resolve("lib/envB.jar"));
        FS.touch(baseDir.resolve("etc/envB.xml"));
        Files.write(baseDir.resolve("modules/feature-envB.mod"),
            Arrays.asList(
                "[provides]",
                "feature-envB",
                "[environment]",
                "envB",
                "[depends]",
                "main",
                "[xml]",
                "etc/envB.xml",
                "[lib]",
                "lib/envB.jar",
                "[ini]",
                "feature.option=envB"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--module=feature-envA,feature-envB"
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
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        assertThat(results.getEnvironments(), hasSize(2));
        for (String e : List.of("envA", "envB"))
        {
            Environment environment = results.getEnvironment(e);
            assertThat(environment, notNullValue());
            assertThat(environment.getName(), is(e));
            assertThat(environment.getClasspath().getElements(), contains(baseDir.resolve("lib/%s.jar".formatted(e)).toFile()));
            assertThat(environment.getXmlFiles(), contains(baseDir.resolve("etc/%s.xml".formatted(e))));
            assertThat(environment.getProperties().getProp("feature.option").value, is(e));
        }
    }
}
