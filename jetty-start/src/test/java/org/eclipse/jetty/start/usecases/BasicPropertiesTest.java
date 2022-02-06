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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

public class BasicPropertiesTest extends AbstractUseCase
{
    @Test
    public void testBasicPropertiesTest() throws Exception
    {
        setupStandardHomeDir();

        Files.write(baseDir.resolve("start.ini"),
            Arrays.asList(
                "--module=main",
                "jetty.http.port=${port}"
            ),
            StandardCharsets.UTF_8);

        // === Execute Main
        List<String> runArgs = Arrays.asList(
            "other=value",
            "port=9090",
            "add+=beginning",
            "add+=middle",
            "add+=end",
            "list+=,one",
            "list+=,two",
            "list+=,three",
            "name?=value",
            "name?=enoughAlready",
            "name0=/",
            "name1=${name0}foo",
            "name2=${name1}/bar",
            "-DSYSTEM=${name}",
            "-DSYSTEM?=IGNORED",
            "-DPRESET?=${SYSTEM}"
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
        expectedProperties.add("port=9090");
        expectedProperties.add("other=value");
        expectedProperties.add("jetty.http.port=9090");
        expectedProperties.add("add=beginningmiddleend");
        expectedProperties.add("list=one,two,three");
        expectedProperties.add("name=value");
        expectedProperties.add("name0=/");
        expectedProperties.add("name1=/foo");
        expectedProperties.add("name2=/foo/bar");
        expectedProperties.add("SYSTEM=value");
        expectedProperties.add("PRESET=value");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate System Properties
        assertThat("System Property [SYSTEM]", System.getProperty("SYSTEM"), equalTo("value"));
        assertThat("System Property [PRESET]", System.getProperty("PRESET"), equalTo("value"));
    }
}
