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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Various Home + Base use cases
 */
public class TestUseCases
{
    public static Stream<Arguments> getCases()
    {
        File usecases = MavenTestingUtils.getTestResourceDir("usecases/");
        File[] cases = usecases.listFiles((dir, name) -> name.endsWith(".assert.txt"));
        Arrays.sort(cases);

        List<Arguments> ret = new ArrayList<>();
        for (File assertTxt : cases)
        {
            String caseName = assertTxt.getName().replace(".assert.txt", "");
            ret.add(Arguments.of(caseName));
        }

        return ret.stream();
    }

    @ParameterizedTest
    @MethodSource("getCases")
    public void testUseCase(String caseName) throws Exception
    {
        String baseName = caseName.replaceFirst("\\..*$", "");
        File assertFile = MavenTestingUtils.getTestResourceFile("usecases/" + caseName + ".assert.txt");

        Path homeDir = MavenTestingUtils.getTestResourceDir("dist-home").toPath().toRealPath();

        Path baseSrcDir = MavenTestingUtils.getTestResourceDir("usecases/" + baseName).toPath().toRealPath();
        Path baseDir = MavenTestingUtils.getTargetTestingPath(caseName);
        org.eclipse.jetty.toolchain.test.FS.ensureEmpty(baseDir);
        org.eclipse.jetty.toolchain.test.IO.copyDir(baseSrcDir.toFile(), baseDir.toFile());

        System.setProperty("jetty.home", homeDir.toString());
        System.setProperty("jetty.base", baseDir.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalStream = StartLog.setStream(new PrintStream(out));
        try
        {
            prepare(caseName);

            Main main = new Main();
            List<String> cmdLine = new ArrayList<>();
            // cmdLine.add("--debug");

            // If there is a "{caseName}.cmdline.txt" then these
            // entries are extra command line argument to use for
            // the actual testcase
            cmdLine.addAll(lines(caseName + ".cmdline.txt"));
            StartArgs args = main.processCommandLine(cmdLine);
            args.getAllModules().checkEnabledModules();
            BaseHome baseHome = main.getBaseHome();

            StartLog.setStream(originalStream);
            String output = out.toString(UTF_8);
            ConfigurationAssert.assertConfiguration(baseHome, args, output, assertFile);
        }
        catch (Exception e)
        {
            List<String> expectedExceptions = lines(assertFile).stream().filter(s -> s.startsWith("EX|")).collect(toList());
            if (expectedExceptions.isEmpty())
                throw e;

            for (String ex : expectedExceptions)
            {
                ex = ex.substring(3);
                if (!e.toString().contains(ex))
                {
                    System.err.println(out.toString(UTF_8));
                    assertThat(e.toString(), Matchers.containsString(ex));
                }
            }
        }
        finally
        {
            StartLog.setStream(originalStream);
        }
    }

    private void prepare(String caseName) throws Exception
    {
        // If there is a "{caseName}.prepare.txt" then use those
        // lines as if you are calling start.jar once to setup
        // the base directory.
        List<String> prepareArgs = lines(caseName + ".prepare.txt");
        if (!prepareArgs.isEmpty())
        {
            Main main = new Main();
            List<String> cmdLine = new ArrayList<>();
            cmdLine.add("--testing-mode");
            cmdLine.addAll(prepareArgs);

            main.start(main.processCommandLine(cmdLine));
        }
    }

    private List<String> lines(String filename) throws IOException
    {
        return lines(MavenTestingUtils.getTestResourcesPath().resolve("usecases" + File.separator + filename).toFile());
    }

    private List<String> lines(File file) throws IOException
    {
        if (!file.exists() || !file.canRead())
            return Collections.emptyList();
        List<String> ret = new ArrayList<>();
        try (FileReader reader = new FileReader(file);
             BufferedReader buf = new BufferedReader(reader))
        {
            String line;
            while ((line = buf.readLine()) != null)
            {
                line = line.trim();
                if (line.startsWith("#"))
                    continue; // skip this line
                if (line.length() > 0)
                {
                    ret.add(line);
                }
            }
        }
        return ret;
    }
}
