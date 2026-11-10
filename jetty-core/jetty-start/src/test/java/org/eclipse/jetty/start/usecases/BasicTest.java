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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.CommandLineBuilder;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.UsageException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.toolchain.test.PathMatchers.isDirectory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BasicTest extends AbstractUseCase
{
    private void setupDistHome() throws IOException
    {
        setupStandardHomeDir();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);
    }

    @Test
    public void testBasicProcessing() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        ExecResults results = exec(runArgs, true);

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

        // === Validate Specific Jetty Base Files/Dirs Exist
        assertThat("Required Directory: maindir/", results.baseHome.getPath("maindir/"), isDirectory());

        // === Validate home/base property uri values
        Props props = results.startArgs.getJettyEnvironment().getProperties();

        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(results.baseHome.getHome()));
        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.home.uri)", props.getString("jetty.home.uri") + "/", is(results.baseHome.getHomePath().toUri().toString()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(results.baseHome.getBase()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.base.uri)", props.getString("jetty.base.uri") + "/", is(results.baseHome.getBasePath().toUri().toString()));

        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(results.baseHome.getHome()));
        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(not(startsWith("file:"))));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(results.baseHome.getBase()));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(not(startsWith("file:"))));
    }

    @Test
    public void testAddModuleDoesNotExist() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            --modules=does-not-exist
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        UsageException usage = assertThrows(UsageException.class, () ->
        {
            ExecResults results = exec(runArgs, true);
            if (results.exception != null)
            {
                throw results.exception;
            }
        });
        assertThat(usage.getMessage(), containsString("Unknown module=[does-not-exist]"));
    }

    @Test
    public void testAddModuleDoesNotExistMultiple() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            --modules=does-not-exist
            --modules=also-not-present
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        UsageException usage = assertThrows(UsageException.class, () ->
        {
            ExecResults results = exec(runArgs, true);
            if (results.exception != null)
            {
                throw results.exception;
            }
        });
        assertThat(usage.getMessage(), containsString("Unknown modules=[does-not-exist, also-not-present]"));
    }

    @Test
    public void testProvidersUsingDefault() throws Exception
    {
        Path homePath = MavenPaths.findTestResourceDir("providers-home");

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=server
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("jetty.home=" + homePath);
        runArgs.add("--create-files");
        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(FS.separators("${jetty.home}/etc/logging-a.xml"));
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, empty());

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("logging.prop=a");
        expectedProperties.add("logging.a=true");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testProvidersUsingSpecific() throws Exception
    {
        Path homePath = MavenPaths.findTestResourceDir("providers-home");

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=server
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("jetty.home=" + homePath);
        runArgs.add("--create-files");
        runArgs.add("--modules=logging-b");
        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(FS.separators("${jetty.home}/etc/logging-b.xml"));
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, empty());

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("logging.prop=b");
        expectedProperties.add("logging.b=true");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));
    }

    @Test
    public void testWithCommandLine() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");

        // JVM args
        runArgs.add("--exec");
        runArgs.add("-Xms1g");
        runArgs.add("-Xmx1g");

        // Arbitrary Libs
        Path extraJar = MavenPaths.findTestResourceFile("extra-libs/example.jar").toRealPath();
        Path extraDir = MavenPaths.findTestResourceDir("extra-resources").toRealPath();

        assertThat("Extra Jar exists: " + extraJar, Files.exists(extraJar), is(true));
        assertThat("Extra Dir exists: " + extraDir, Files.exists(extraDir), is(true));

        String lib = "--libs=" + extraJar + File.pathSeparator + extraDir;
        runArgs.add(lib);

        // Arbitrary XMLs
        runArgs.add("config.xml");
        runArgs.add("config-foo.xml");
        runArgs.add("config-bar.xml");

        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml"),
            FS.separators("${jetty.home}/etc/config.xml"),
            FS.separators("${jetty.home}/etc/config-foo.xml"),
            FS.separators("${jetty.home}/etc/config-bar.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            extraJar.toString(),
            extraDir.toString(),
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

        // === Validate Specific Jetty Base Files/Dirs Exist
        assertThat("Required Directory: maindir/", results.baseHome.getPath("maindir/"), isDirectory());

        // === Validate JVM args
        List<String> expectedJvmArgs = List.of(
            "-Xms1g",
            "-Xmx1g"
        );
        List<String> actualJvmArgs = new ArrayList<>(results.startArgs.getJvmArgSources().keySet());
        assertThat("JVM Args", actualJvmArgs, contains(expectedJvmArgs.toArray()));
    }

    @Test
    public void testWithModulesFromCommandLine() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        runArgs.add("java.version=1.8.0_31");

        // Modules
        runArgs.add("--modules=optional,extra");

        ExecResults results = exec(runArgs, true);

        // === Validate Resulting XMLs
        List<String> expectedXmls = List.of(
            FS.separators("${jetty.home}/etc/optional.xml"),
            FS.separators("${jetty.home}/etc/base.xml"),
            FS.separators("${jetty.home}/etc/main.xml"),
            FS.separators("${jetty.home}/etc/extra.xml")
        );
        List<String> actualXmls = results.getXmls();
        assertThat("XML Resolution Order", actualXmls, contains(expectedXmls.toArray()));

        // === Validate Resulting LIBs
        List<String> expectedLibs = List.of(
            FS.separators("${jetty.home}/lib/optional.jar"),
            FS.separators("${jetty.home}/lib/base.jar"),
            FS.separators("${jetty.home}/lib/main.jar"),
            FS.separators("${jetty.home}/lib/other.jar"),
            FS.separators("${jetty.home}/lib/extra/extra0.jar"),
            FS.separators("${jetty.home}/lib/extra/extra1.jar")
        );
        List<String> actualLibs = results.getLibs();
        assertThat("Libs", actualLibs, containsInAnyOrder(expectedLibs.toArray()));

        // === Validate Resulting Properties
        Set<String> expectedProperties = new HashSet<>();
        expectedProperties.add("extra.prop=value0");
        expectedProperties.add("main.prop=value0");
        expectedProperties.add("optional.prop=value0");
        List<String> actualProperties = results.getProperties();
        assertThat("Properties", actualProperties, containsInAnyOrder(expectedProperties.toArray()));

        // === Validate Specific Jetty Base Files/Dirs Exist
        assertThat("Required Directory: maindir/", results.baseHome.getPath("maindir/"), isDirectory());
    }

    @Test
    public void testArgLines() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        runArgs.add("java.version=21.0.1");
        runArgs.add("--modules=optional,extra");
        runArgs.add("--arglines");

        ExecResults results = exec(runArgs, true);
        assertThat("No exception", results.exception, is(nullValue()));
        assertThat("DryRun mode", results.startArgs.getDryRun(), is(StartArgs.DryRun.ARGLINES));
        // "java" is not compatible with java @arglines and must be excluded
        assertThat("arglines parts", results.startArgs.getDryRunParts(), not(hasItem("java")));

        String argLines = renderArgLines(results);
        List<String> lines = argLines.lines().toList();

        // The java binary must NOT be present (it is the thing that reads the @argfile)
        assertThat("no java binary line", lines, not(hasItem(endsWith(File.separator + "java"))));
        assertThat("no java.exe binary line", lines, not(hasItem(endsWith(File.separator + "java.exe"))));
        // The main class must be present
        assertThat("main class line", lines, hasItem(containsString(results.startArgs.getMainClassname())));
        // Every non-empty line is a single token (no unquoted whitespace that would re-split)
        for (String line : lines)
            assertThat("single token per line: [" + line + "]", isSingleArgFileToken(line), is(true));
    }

    @Test
    public void testArgLinesParts() throws Exception
    {
        setupDistHome();

        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            """, UTF_8);

        List<String> runArgs = new ArrayList<>();
        runArgs.add("--create-files");
        runArgs.add("java.version=21.0.1");
        runArgs.add("--modules=optional,extra");
        runArgs.add("--arglines=path,main");

        ExecResults results = exec(runArgs, true);
        assertThat("No exception", results.exception, is(nullValue()));
        assertThat("arglines parts", results.startArgs.getDryRunParts(), containsInAnyOrder("path", "main"));

        String argLines = renderArgLines(results);
        // "main" part present
        assertThat(argLines, containsString(results.startArgs.getMainClassname()));
        // "opts" part excluded, so no -D system properties
        assertThat(argLines, not(containsString("-Djetty.home")));
    }

    @Test
    public void testArgLinesJavaPartRejected() throws Exception
    {
        setupDistHome();

        List<String> runArgs = new ArrayList<>();
        runArgs.add("--arglines=java");

        ExecResults results = exec(runArgs, false);
        assertThat("UsageException expected", results.exception, instanceOf(UsageException.class));
    }

    @Test
    public void testArgLinesCombinedWithDryRunRejected() throws Exception
    {
        setupDistHome();

        // --dry-run (shell format) and --arglines (java @argfile format) share the dry-run
        // state, so combining them must be rejected rather than silently producing one mode's
        // output with the other's parts.
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--dry-run");
        runArgs.add("--arglines");

        ExecResults results = exec(runArgs, false);
        assertThat("UsageException expected", results.exception, instanceOf(UsageException.class));
        assertThat(results.exception.getMessage(), containsString("Cannot combine"));
    }

    @Test
    public void testArgLinesBannedInIniFile() throws Exception
    {
        setupDistHome();

        // --arglines is a command-line-only option; it must not be accepted from a start.ini.
        Files.writeString(baseDir.resolve("start.ini"),
            """
            --modules=main
            --arglines
            """, UTF_8);

        ExecResults results = exec(List.of(), false);
        assertThat("UsageException expected", results.exception, instanceOf(UsageException.class));
        assertThat(results.exception.getMessage(), containsString("--arglines"));
        assertThat(results.exception.getMessage(), containsString("not allowed"));
    }

    /**
     * Render the {@code java @arglines} output using the same code path that
     * {@link org.eclipse.jetty.start.Main} uses.
     */
    private String renderArgLines(ExecResults results) throws IOException
    {
        CommandLineBuilder cmd = results.startArgs.getMainArgs(results.startArgs.getDryRunParts());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bout, true, UTF_8))
        {
            cmd.writeArgLines(out);
        }
        return bout.toString(UTF_8);
    }

    /**
     * Verify a rendered arg-file line tokenizes back to exactly one token (an empty line is allowed).
     */
    private static boolean isSingleArgFileToken(String line)
    {
        if (line.isEmpty())
            return true;
        // Quoted token: a line wrapped in double quotes is a single token.
        if (line.startsWith("\"") && line.endsWith("\"") && line.length() >= 2)
            return true;
        // Otherwise it must contain no unquoted argfile separators.
        return line.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\f');
    }

    @Test
    public void testHomeWithSpaces() throws Exception
    {
        homeDir = testdir.resolve("jetty home with spaces");
        FS.ensureDirExists(homeDir);

        setupDistHome();

        // === Execute Main
        List<String> runArgs = new ArrayList<>();
        runArgs.add("--modules=main");
        ExecResults results = exec(runArgs, true);

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

        // === Validate home/base property uri values
        Props props = results.startArgs.getJettyEnvironment().getProperties();

        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(results.baseHome.getHome()));
        assertThat("Props(jetty.home)", props.getString("jetty.home"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.home.uri)", props.getString("jetty.home.uri") + "/", is(results.baseHome.getHomePath().toUri().toString()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(results.baseHome.getBase()));
        assertThat("Props(jetty.base)", props.getString("jetty.base"), is(not(startsWith("file:"))));
        assertThat("Props(jetty.base.uri)", props.getString("jetty.base.uri") + "/", is(results.baseHome.getBasePath().toUri().toString()));

        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(results.baseHome.getHome()));
        assertThat("System.getProperty(jetty.home)", System.getProperty("jetty.home"), is(not(startsWith("file:"))));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(results.baseHome.getBase()));
        assertThat("System.getProperty(jetty.base)", System.getProperty("jetty.base"), is(not(startsWith("file:"))));
    }
}
