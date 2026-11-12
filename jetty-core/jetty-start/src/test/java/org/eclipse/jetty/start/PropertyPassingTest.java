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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
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
        Files.writeString(ini, iniBody, UTF_8);

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
        // The property is defined as `test.config=${jetty.base}/etc/config.ini`
        // so we must maintain the slashes for the `/etc/config.ini` portion (even if it is running on windows)
        assertThat(output, containsString("test.config=%s/etc/config.ini".formatted(base)));
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
        Files.writeString(module, moduleBody, UTF_8);
        Path ini = base.resolve("start.d/config.ini");
        FS.ensureDirectoryExists(ini.getParent());
        String iniBody = """
            # Enabling a single module (that does nothing) to let start.jar run
            --module=env-config
            # TESTING THIS (it should expand the ${jetty.base} portion
            test.config=${jetty.base}/etc/config.ini
            """;
        Files.writeString(ini, iniBody, UTF_8);

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
        // The property is defined as `test.config=${jetty.base}/etc/config.ini`
        // so we must maintain the slashes for the `/etc/config.ini` portion (even if it is running on windows)
        assertThat(output, containsString("test.config=%s/etc/config.ini".formatted(base)));
    }

    // café-€-naïve : a value with non-ASCII code points whose encoding matters.
    private static final String MARKER_VALUE = "café-€-naïve";

    // 中文密码 ("Chinese password") : non-ASCII code points well outside Latin-1 (a keystore password
    // is a realistic carrier of such a value).
    private static final String CHINESE_VALUE = "中文密码";

    /**
     * When the JVM that generates the arguments file and the JVM that runs {@code java @file}
     * share a native encoding (both UTF-8 here), a non-ASCII property value round-trips.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void testArgFileSurvivesMatchingNativeEncoding() throws Exception
    {
        Path argFile = workDir.getEmptyPathDir().resolve("same.args");
        generateArgFile(argFile, "C.UTF-8", "test.marker=" + MARKER_VALUE);
        byte[] output = runPropertyDump(argFile, "C.UTF-8");

        byte[] needle = ("test.marker=" + MARKER_VALUE).getBytes(UTF_8);
        assertThat("value preserved under matching native encoding", containsSequence(output, needle), is(true));
    }

    /**
     * Pins the documented portability caveat: generating under UTF-8 and then running
     * {@code java @file} under an ASCII native encoding (LANG=C) misreads the non-ASCII bytes,
     * so the original value no longer appears.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void testArgFileCorruptedByMismatchedNativeEncoding() throws Exception
    {
        Path argFile = workDir.getEmptyPathDir().resolve("mismatch.args");
        generateArgFile(argFile, "C.UTF-8", "test.marker=" + MARKER_VALUE);
        byte[] output = runPropertyDump(argFile, "C");

        byte[] needle = ("test.marker=" + MARKER_VALUE).getBytes(UTF_8);
        assertThat("value corrupted under mismatched native encoding", containsSequence(output, needle), is(false));
    }

    /**
     * As {@link #testArgFileSurvivesMatchingNativeEncoding()} but with Chinese characters (well
     * outside Latin-1): a non-ASCII property value such as a keystore password round-trips when the
     * generating and running JVMs share a UTF-8 native encoding.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void testArgFileSurvivesMatchingNativeEncodingChinese() throws Exception
    {
        Path argFile = workDir.getEmptyPathDir().resolve("same-cjk.args");
        generateArgFile(argFile, "C.UTF-8", "test.marker=" + CHINESE_VALUE);
        byte[] output = runPropertyDump(argFile, "C.UTF-8");

        byte[] needle = ("test.marker=" + CHINESE_VALUE).getBytes(UTF_8);
        assertThat("Chinese value preserved under matching native encoding", containsSequence(output, needle), is(true));
    }

    /**
     * As {@link #testArgFileCorruptedByMismatchedNativeEncoding()} but with Chinese characters:
     * generating under UTF-8 and running {@code java @file} under an ASCII native encoding
     * ({@code LANG=C}) misreads the bytes, so the original value no longer appears.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void testArgFileCorruptedByMismatchedNativeEncodingChinese() throws Exception
    {
        Path argFile = workDir.getEmptyPathDir().resolve("mismatch-cjk.args");
        generateArgFile(argFile, "C.UTF-8", "test.marker=" + CHINESE_VALUE);
        byte[] output = runPropertyDump(argFile, "C");

        byte[] needle = ("test.marker=" + CHINESE_VALUE).getBytes(UTF_8);
        assertThat("Chinese value corrupted under mismatched native encoding", containsSequence(output, needle), is(false));
    }

    private void generateArgFile(Path argFile, String lang, String... properties) throws Exception
    {
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-Dmain.class=" + PropertyDump.class.getName());
        commands.add("-cp");
        commands.add(getClassPath());
        commands.add(getStartJarBin());
        // Include the "main" part so the file itself names the main class: the launcher only
        // expands @file in launcher position (before the main class), not as an application arg.
        commands.add("--argfile=main,args");
        commands.addAll(List.of(properties));

        ProcessBuilder builder = new ProcessBuilder(commands);
        builder.directory(MavenPaths.findTestResourceDir("empty.home").toFile());
        builder.environment().put("LANG", lang);
        builder.environment().put("LC_ALL", lang);
        // Capture the raw native-encoded bytes on disk (not a String-decoded copy).
        builder.redirectOutput(argFile.toFile());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        int exit = builder.start().waitFor();
        assertThat("generate (--argfile) exit code", exit, is(0));
    }

    private byte[] runPropertyDump(Path argFile, String lang) throws Exception
    {
        // "java -cp <cp> @file": the @file is in launcher position, so it expands to the main
        // class (PropertyDump, from the "main" part) plus the inlined property args. The test
        // classpath is supplied explicitly, avoiding reliance on a start.jar-computed classpath.
        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());
        commands.add("-cp");
        commands.add(getClassPath());
        commands.add("@" + argFile.toAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(commands);
        builder.directory(MavenPaths.findTestResourceDir("empty.home").toFile());
        builder.environment().put("LANG", lang);
        builder.environment().put("LC_ALL", lang);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process pid = builder.start();
        byte[] out = pid.getInputStream().readAllBytes();
        pid.waitFor();
        return out;
    }

    /**
     * @return whether the byte sequence {@code needle} occurs anywhere within {@code haystack}.
     * <p>
     * The encoding tests compare the child's raw stdout <em>bytes</em> against the UTF-8 bytes
     * of the expected value on purpose. Decoding the child's output into a {@code String} first
     * (and using {@link String#contains}) would introduce yet another charset step -- the one
     * used to read the child's stdout -- which is the very variable under test, so a mismatch
     * there could mask or fake a result. Working at the byte level keeps the assertion about the
     * exact bytes the child emitted and nothing else.
     * <p>
     * jetty-util's {@code SearchPattern} is deliberately not used: its documented contract is
     * that the pattern is {@link java.nio.charset.StandardCharsets#US_ASCII} encoded, but the
     * needle here is non-ASCII UTF-8 bytes - precisely what this test exercises - so relying on
     * it would be leaning on an off-contract behavior for the one thing under test.
     */
    private static boolean containsSequence(byte[] haystack, byte[] needle)
    {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++)
        {
            for (int j = 0; j < needle.length; j++)
            {
                if (haystack[i + j] != needle[j])
                    continue outer;
            }
            return true;
        }
        return false;
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
            // System.out.println("Command line: " + cline);
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
