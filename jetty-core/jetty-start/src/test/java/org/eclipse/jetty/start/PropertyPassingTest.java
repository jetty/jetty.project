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

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class PropertyPassingTest
{
    private static class ConsoleCapture implements Runnable
    {
        private String mode;
        private BufferedReader reader;
        private StringWriter output;
        private CountDownLatch latch = new CountDownLatch(1);

        public ConsoleCapture(String mode, InputStream is)
        {
            this.mode = mode;
            this.reader = new BufferedReader(new InputStreamReader(is));
            this.output = new StringWriter();
        }

        @Override
        public void run()
        {
            String line;
            try (PrintWriter out = new PrintWriter(output))
            {
                while ((line = reader.readLine()) != (null))
                {
                    out.println(line);
                    out.flush();
                }
            }
            catch (IOException ignore)
            {
                /* ignore */
            }
            finally
            {
                IO.close(reader);
                latch.countDown();
            }
        }

        public String getConsoleOutput() throws InterruptedException
        {
            latch.await(30, TimeUnit.SECONDS);
            return output.toString();
        }

        public ConsoleCapture start()
        {
            Thread thread = new Thread(this, "ConsoleCapture/" + mode);
            thread.start();
            return this;
        }
    }

    public WorkDir workDir;

    @Test
    public void testAsJvmArg() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add("-Dtest.foo=bar"); // TESTING THIS
        commands.add(getStartJarBin());
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat("output", output, containsString("foo=bar"));
    }

    @Test
    public void testAsCommandLineArg() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add("test.foo=bar"); // TESTING THIS
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat("output", output, containsString("foo=bar"));
    }

    @Test
    public void testAsDashDCommandLineArg() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add("-Dtest.foo=bar"); // TESTING THIS
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat(output, containsString("test.foo=bar"));
    }

    @Test
    public void testExpandPropertyArg() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-Dtest.dir=/opt/dists/jetty");
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add("test.config=${test.dir}/etc/config.ini"); // TESTING THIS
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat(output, containsString("test.config=/opt/dists/jetty/etc/config.ini"));
    }

    @Test
    public void testExpandPropertyDArg() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-Dtest.dir=/opt/dists/jetty");
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add("-Dtest.config=${test.dir}/etc/config.ini"); // TESTING THIS
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat(output, containsString("test.config=/opt/dists/jetty/etc/config.ini"));
    }

    @Test
    public void testExpandPropertyStartIni() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");
        Path base = workDir.getEmptyPathDir();
        Path ini = base.resolve("start.d/config.ini");
        FS.ensureDirectoryExists(ini.getParent());
        String iniBody = """
            # Enabling a single module (that does nothing) to let start.jar run
            --module=empty
            # TESTING THIS (it should expand the ${jetty.base} portion
            test.config=${jetty.base}/etc/config.ini
            """;
        Files.writeString(ini, iniBody, StandardCharsets.UTF_8);

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-Djetty.base=" + base);
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        Path expectedPath = base.resolve("etc/config.ini");
        assertThat(output, containsString("test.config=" + expectedPath));
    }

    @Test
    public void testExpandEnvProperty() throws IOException, InterruptedException
    {
        Path bogusXml = MavenPaths.findTestResourceFile("bogus.xml");
        Path base = workDir.getEmptyPathDir();
        Path module = base.resolve("modules/env-config.mod");
        FS.ensureDirectoryExists(module.getParent());
        String moduleBody = """
            [environment]
            eex
            
            [ini-template]
            # configuration option
            # test.config=${jetty.home}/etc/eex-config.ini
            """;
        Files.writeString(module, moduleBody, StandardCharsets.UTF_8);
        Path ini = base.resolve("start.d/config.ini");
        FS.ensureDirectoryExists(ini.getParent());
        String iniBody = """
            # Enabling a single module (that does nothing) to let start.jar run
            --module=env-config
            # TESTING THIS (it should expand the ${jetty.base} portion
            test.config=${jetty.base}/etc/config.ini
            """;
        Files.writeString(ini, iniBody, StandardCharsets.UTF_8);

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-Djetty.base=" + base);
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add(bogusXml.toAbsolutePath().toString());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        Path expectedPath = base.resolve("etc/config.ini");
        assertThat(output, containsString("test.config=" + expectedPath));
    }

    private String getClassPath()
    {
        return String.join(
            File.pathSeparator,
            List.of(
                MavenPaths.projectBase().resolve("target/classes").toString(),
                MavenPaths.projectBase().resolve("target/test-classes").toString(),
                MavenPaths.projectBase().resolve("target/jetty-util").toString()
            )
        );
    }

    protected void addDebug(List<String> commands)
    {
        commands.add("-Xdebug");
        commands.add("-Xrunjdwp:server=y,transport=dt_socket,address=4000,suspend=y");
    }

    private String collectRunOutput(List<String> commands) throws IOException, InterruptedException
    {
        StringBuilder cline = new StringBuilder();
        for (String command : commands)
        {
            cline.append(command).append(" ");
        }

        ProcessBuilder builder = new ProcessBuilder(commands);
        // Set PWD
        builder.directory(MavenPaths.findTestResourceDir("empty.home").toFile());
        Process pid = builder.start();

        ConsoleCapture stdOutPump = new ConsoleCapture("STDOUT", pid.getInputStream()).start();
        ConsoleCapture stdErrPump = new ConsoleCapture("STDERR", pid.getErrorStream()).start();

        int exitCode = pid.waitFor();
        if (exitCode != 0)
        {
            System.out.println("Command line: " + cline);
            System.out.printf("STDERR: [" + stdErrPump.getConsoleOutput() + "]%n");
            System.out.printf("STDOUT: [" + stdOutPump.getConsoleOutput() + "]%n");
            assertThat("Exit code", exitCode, is(0));
        }
        stdErrPump.getConsoleOutput();
        return stdOutPump.getConsoleOutput();
    }

    private String getStartJarBin()
    {
        return org.eclipse.jetty.start.Main.class.getName();
    }

    private String getJavaBin()
    {
        return CommandLineBuilder.findJavaBin();
    }
}
