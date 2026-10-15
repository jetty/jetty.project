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

import org.eclipse.jetty.start.StartEnvironment;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

public class FilesWithPropertiesTest extends AbstractUseCase
{
    @SuppressWarnings("checkstyle:LeftCurly")
    @Test
    public void testFileWithProperty() throws Exception
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("etc"));
        FS.ensureDirExists(baseDir.resolve("lib"));
        FS.ensureDirExists(baseDir.resolve("modules"));

        FS.touch(baseDir.resolve("lib/foo-1.1.jar"));

        Files.writeString(baseDir.resolve("modules/feature-foo.mod"),
            """
            [environment]
            foo
            [depends]
            main
            [ini]
            foo.version?=1.1
            [files]
            maven://org.example/foo/${foo.version}/jar|lib/foo-${foo.version}.jar
            [lib]
            lib/foo-${foo.version}.jar
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = List.of(
            "--modules=feature-foo"
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
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        assertThat(results.getEnvironments(), hasSize(1));
        StartEnvironment fooEnv = results.getEnvironment("foo");
        assertThat(fooEnv, notNullValue());
        List<String> expectedFooLibs = List.of(
            FS.separators("${jetty.base}/lib/foo-1.1.jar")
        );
        List<String> actualFooLibs = fooEnv.getClasspath().getElements().stream()
            .map(path -> results.baseHome.toShortForm(path))
            .toList();
        assertThat("Foo.Libs", actualFooLibs, containsInAnyOrder(expectedFooLibs.toArray()));
    }
}
