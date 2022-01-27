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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.start.FS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;

public class PropertyOverrideTest extends AbstractUseCase
{
    /**
     * Ensure that a property specified in a `*.mod` file can be overridden by the command line.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "jetty.sslContext.keyStorePassword=invalid",
        "jetty.sslContext.keyStorePassword?=invalid"
    })
    public void testBasicModuleIniPropertyOverrideWithCommandLine(String propRef) throws Exception
    {
        setupStandardHomeDir();

        Files.write(homeDir.resolve("modules/ssl.mod"),
            List.of(
                "[depend]",
                "main",
                "[ini-template]",
                "# jetty.sslContext.keyStorePassword=default"
            ),
            StandardCharsets.UTF_8);

        FS.ensureDirectoryExists(baseDir.resolve("modules"));

        Files.write(baseDir.resolve("modules/ssl-ini.mod"),
            List.of(
                "[depend]",
                "ssl",
                "[ini]",
                propRef
            ),
            StandardCharsets.UTF_8);

        FS.ensureDirectoryExists(baseDir.resolve("start.d"));
        Files.write(baseDir.resolve("start.d/main.ini"),
            List.of(
                "--module=ssl-ini"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> commandLine = List.of(
            // this "command line" property value should win
            "jetty.sslContext.keyStorePassword=storepwd"
        );
        ExecResults results = exec(commandLine, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, ordered(expectedXmls));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, ordered(expectedLibs));

        // === Validate Resulting Properties
        List<String> expectedProperties = new ArrayList<>();
        // we should see value from command line
        expectedProperties.add("jetty.sslContext.keyStorePassword=storepwd");
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();

        Collections.sort(expectedProperties);
        Collections.sort(actualProperties);
        assertThat("Properties", actualProperties, ordered(expectedProperties));
    }

    /**
     * Ensure that a property specified in a `*.mod` file can be overridden by the ini configuration.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "jetty.sslContext.keyStorePassword=invalid",
        "jetty.sslContext.keyStorePassword?=invalid"
    })
    public void testBasicModuleIniPropertyOverrideWithStartdIni(String propRef) throws Exception
    {
        setupStandardHomeDir();

        Files.write(homeDir.resolve("modules/ssl.mod"),
            List.of(
                "[depend]",
                "main",
                "[ini-template]",
                "# jetty.sslContext.keyStorePassword=default"
            ),
            StandardCharsets.UTF_8);

        FS.ensureDirectoryExists(baseDir.resolve("modules"));

        Files.write(baseDir.resolve("modules/ssl-ini.mod"),
            List.of(
                "[depend]",
                "ssl",
                "[ini]",
                propRef
            ),
            StandardCharsets.UTF_8);

        FS.ensureDirectoryExists(baseDir.resolve("start.d"));
        Files.write(baseDir.resolve("start.d/main.ini"),
            List.of(
                "--module=ssl-ini",
                // this should override mod default
                "jetty.sslContext.keyStorePassword=storepwd"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> commandLine = List.of();
        ExecResults results = exec(commandLine, false);

        // === Validate Resulting XMLs
        List<String> expectedXmls = Arrays.asList(
            "${jetty.home}/etc/base.xml",
            "${jetty.home}/etc/main.xml"
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, ordered(expectedXmls));

        // === Validate Resulting LIBs
        List<String> expectedLibs = Arrays.asList(
            "${jetty.home}/lib/base.jar",
            "${jetty.home}/lib/main.jar",
            "${jetty.home}/lib/other.jar"
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, ordered(expectedLibs));

        // === Validate Resulting Properties
        List<String> expectedProperties = new ArrayList<>();
        // we should see value from command line
        expectedProperties.add("jetty.sslContext.keyStorePassword=storepwd");
        expectedProperties.add("main.prop=value0");
        List<String> actualProperties = results.getProperties();

        Collections.sort(expectedProperties);
        Collections.sort(actualProperties);
        assertThat("Properties", actualProperties, ordered(expectedProperties));
    }
}
