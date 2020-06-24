//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.start.util.RebuildTestResources;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test bad configuration scenarios.
 */
public class TestBadUseCases
{
    public static Stream<Arguments> getCases()
    {
        List<Object[]> ret = new ArrayList<>();

        ret.add(new Object[]{
            "http2",
            "Invalid Java version",
            new String[]{"java.version=0.0.0_0"}
        });

        ret.add(new Object[]{
            "versioned-modules-too-new",
            "Module [http3] specifies jetty version [10.0] which is newer than this version of jetty [" + RebuildTestResources.JETTY_VERSION + "]",
            null
        });

        return ret.stream().map(Arguments::of);
    }

    // TODO unsure how this failure should be handled
    @Disabled
    @ParameterizedTest
    @MethodSource("getCases")
    public void testBadConfig(String caseName, String expectedErrorMessage, String[] commandLineArgs) throws Exception
    {
        File homeDir = MavenTestingUtils.getTestResourceDir("dist-home");
        File baseDir = MavenTestingUtils.getTestResourceDir("usecases/" + caseName);

        Main main = new Main();
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("jetty.home=" + homeDir.getAbsolutePath());
        cmdLine.add("jetty.base=" + baseDir.getAbsolutePath());
        // cmdLine.add("--debug");

        if (commandLineArgs != null)
        {
            for (String arg : commandLineArgs)
            {
                cmdLine.add(arg);
            }
        }

        UsageException x = assertThrows(UsageException.class, () -> main.processCommandLine(cmdLine));
        assertThat(x.getMessage(), containsString(expectedErrorMessage));
    }
}
