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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.Main;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.StartLog;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractUseCase
{
    public WorkDir workDir;

    protected Path homeDir;
    protected Path baseDir;

    @AfterEach
    public void clearSystemProperties()
    {
        System.setProperty("jetty.home", "");
        System.setProperty("jetty.base", "");
    }

    @BeforeEach
    public void setupTest() throws IOException
    {
        Path testdir = workDir.getEmptyPathDir();

        // Create empty base directory for testcase to use
        baseDir = testdir.resolve("test-base");
        FS.ensureDirExists(baseDir);

        // Create baseline jetty-home directory
        homeDir = testdir.resolve("dist-home");
        FS.ensureDirExists(homeDir);
    }

    protected void setupStandardHomeDir() throws IOException
    {
        Path etc = homeDir.resolve("etc");
        FS.ensureDirExists(etc);
        FS.touch(etc.resolve("base.xml"));
        FS.touch(etc.resolve("config.xml"));
        FS.touch(etc.resolve("config-bar.xml"));
        FS.touch(etc.resolve("config-foo.xml"));
        FS.touch(etc.resolve("extra.xml"));
        FS.touch(etc.resolve("main.xml"));
        FS.touch(etc.resolve("optional.xml"));

        Path lib = homeDir.resolve("lib");
        FS.ensureDirExists(lib);
        FS.touch(lib.resolve("base.jar"));
        FS.touch(lib.resolve("main.jar"));
        FS.touch(lib.resolve("optional.jar"));
        FS.touch(lib.resolve("other.jar"));
        Path libExtra = lib.resolve("extra");
        FS.ensureDirExists(libExtra);
        FS.touch(libExtra.resolve("extra0.jar"));
        FS.touch(libExtra.resolve("extra1.jar"));

        Path modules = homeDir.resolve("modules");
        FS.ensureDirExists(modules);

        Files.write(modules.resolve("base.mod"),
            Arrays.asList(
                "[optional]",
                "optional",
                "[lib]",
                "lib/base.jar",
                "[xml]",
                "etc/base.xml"),
            StandardCharsets.UTF_8);
        Files.write(modules.resolve("extra.mod"),
            Arrays.asList(
                "[depend]",
                "main",
                "[lib]",
                "lib/extra/*.jar",
                "[xml]",
                "etc/extra.xml",
                "[ini]",
                "extra.prop=value0"),
            StandardCharsets.UTF_8);
        Files.write(modules.resolve("main.mod"),
            Arrays.asList(
                "[depend]",
                "base",
                "[optional]",
                "optional",
                "[lib]",
                "lib/main.jar",
                "lib/other.jar",
                "[xml]",
                "etc/main.xml",
                "[files]",
                "maindir/",
                "[ini]",
                "main.prop=value0",
                "[ini-template]",
                "main.prop=valueT"),
            StandardCharsets.UTF_8);
        Files.write(modules.resolve("optional.mod"),
            Arrays.asList(
                "[lib]",
                "lib/optional.jar",
                "[xml]",
                "etc/optional.xml",
                "[ini]",
                "optional.prop=value0"),
            StandardCharsets.UTF_8);
    }

    public static class ExecResults
    {
        public Exception exception;
        public BaseHome baseHome;
        public StartArgs startArgs;
        public String output;

        public List<String> getXmls()
        {
            return startArgs.getXmlFiles().stream()
                .map(p -> baseHome.toShortForm(p))
                .collect(Collectors.toList());
        }

        public List<String> getLibs()
        {
            return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(startArgs.getClasspath().iterator(), Spliterator.ORDERED), false)
                .map(f -> baseHome.toShortForm(f))
                .collect(Collectors.toList());
        }

        public List<String> getProperties()
        {
            Props props = startArgs.getProperties();

            Predicate<Props.Prop> propPredicate = (p) ->
            {
                String name = p.key;
                return !("jetty.home".equals(name) ||
                    "jetty.base".equals(name) ||
                    "jetty.home.uri".equals(name) ||
                    "jetty.base.uri".equals(name) ||
                    "user.dir".equals(name) ||
                    p.source.equals(Props.ORIGIN_SYSPROP) ||
                    name.startsWith("runtime.feature.") ||
                    name.startsWith("java."));
            };

            return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(props.iterator(), Spliterator.ORDERED), false)
                .filter(propPredicate)
                .map((prop) -> prop.key + "=" + props.expand(prop.value))
                .collect(Collectors.toList());
        }

        public List<String> getDownloads()
        {
            return startArgs.getFiles().stream()
                .filter(Objects::nonNull)
                .filter(farg -> farg.uri != null)
                .map(farg -> String.format("%s|%s", farg.uri, farg.location))
                .collect(Collectors.toList());
        }
    }

    protected ExecResults exec(List<String> cmdline, boolean start)
    {
        List<String> execArgs = new ArrayList<>();

        if (cmdline.stream().noneMatch((line -> line.startsWith("jetty.home="))))
        {
            execArgs.add("jetty.home=" + homeDir.toString());
        }

        if (cmdline.stream().noneMatch((line -> line.startsWith("jetty.base="))))
        {
            execArgs.add("jetty.base=" + baseDir.toString());
        }
        execArgs.add("--testing-mode");
        // execArgs.add("--debug");
        execArgs.addAll(cmdline);

        ExecResults execResults = new ExecResults();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalStream = StartLog.setStream(new PrintStream(out));
        try
        {
            Main main = new Main();

            execResults.startArgs = main.processCommandLine(execArgs);
            if (start)
            {
                main.start(execResults.startArgs);
            }
            else
            {
                execResults.startArgs.getAllModules().checkEnabledModules();
            }
            execResults.baseHome = main.getBaseHome();

            StartLog.setStream(originalStream);
            execResults.output = out.toString(StandardCharsets.UTF_8.name());
        }
        catch (Exception e)
        {
            execResults.exception = e;
        }
        finally
        {
            StartLog.setStream(originalStream);
        }
        return execResults;
    }

    public static void assertOutputContainsRegex(String output, String regexPattern)
    {
        Pattern pat = Pattern.compile(regexPattern);
        Matcher mat = pat.matcher(output);
        assertTrue(mat.find(), "Output [\n" + output + "]\nContains Regex Match: " + pat.pattern());
    }
}
