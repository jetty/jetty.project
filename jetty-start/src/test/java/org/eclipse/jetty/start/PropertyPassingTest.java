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

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
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

    public WorkDir testingdir;

    @Test
    public void testAsJvmArg() throws IOException, InterruptedException
    {
        File bogusXml = MavenTestingUtils.getTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add("-Dtest.foo=bar"); // TESTING THIS
        commands.add(getStartJarBin());
        commands.add(bogusXml.getAbsolutePath());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat("output", output, containsString("foo=bar"));
    }

    @Test
    public void testAsCommandLineArg() throws IOException, InterruptedException
    {
        File bogusXml = MavenTestingUtils.getTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add("test.foo=bar"); // TESTING THIS
        commands.add(bogusXml.getAbsolutePath());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat("output", output, containsString("foo=bar"));
    }

    @Test
    public void testAsDashDCommandLineArg() throws IOException, InterruptedException
    {
        File bogusXml = MavenTestingUtils.getTestResourceFile("bogus.xml");

        // Setup command line
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        // addDebug(commands);
        commands.add(getStartJarBin());
        commands.add("-Dtest.foo=bar"); // TESTING THIS
        commands.add(bogusXml.getAbsolutePath());

        // Run command, collect output
        String output = collectRunOutput(commands);

        // Test for values
        assertThat(output, containsString("test.foo=bar"));
    }

    private String getClassPath()
    {
        StringBuilder cp = new StringBuilder();
        String pathSep = System.getProperty("path.separator");
        cp.append(MavenTestingUtils.getProjectDir("target/classes"));
        cp.append(pathSep);
        cp.append(MavenTestingUtils.getProjectDir("target/test-classes"));
        cp.append(pathSep);
        cp.append(MavenTestingUtils.getProjectDir("../jetty-util/target/classes")); // TODO horrible hack!
        return cp.toString();
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
        System.out.println("Command line: " + cline);

        ProcessBuilder builder = new ProcessBuilder(commands);
        // Set PWD
        builder.directory(MavenTestingUtils.getTestResourceDir("empty.home"));
        Process pid = builder.start();

        ConsoleCapture stdOutPump = new ConsoleCapture("STDOUT", pid.getInputStream()).start();
        ConsoleCapture stdErrPump = new ConsoleCapture("STDERR", pid.getErrorStream()).start();

        int exitCode = pid.waitFor();
        if (exitCode != 0)
        {
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
