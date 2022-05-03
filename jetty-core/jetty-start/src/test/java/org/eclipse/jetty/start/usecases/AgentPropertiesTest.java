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

public class AgentPropertiesTest extends AbstractUseCase
{
    @Test
    public void testAgentPropertiesTest() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("lib"));
        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.touch(baseDir.resolve("lib/agent-jdk-1.5.jar"));
        FS.touch(baseDir.resolve("lib/agent-jdk-1.6.jar"));
        FS.touch(baseDir.resolve("lib/agent-jdk-1.7.jar"));
        FS.touch(baseDir.resolve("lib/agent-jdk-1.8.jar"));
        Files.write(baseDir.resolve("modules/agent.mod"),
            Arrays.asList(
                "[depend]",
                "main",
                "[lib]",
                "lib/agent-jdk-${java.vm.specification.version}.jar"
            ),
            StandardCharsets.UTF_8);
        Files.write(baseDir.resolve("start.ini"),
            Collections.singletonList(
                "--module=main,agent"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Collections.singletonList(
            "java.vm.specification.version=1.7"
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
            "${jetty.home}/lib/other.jar",
            "${jetty.base}/lib/agent-jdk-1.7.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }
}
