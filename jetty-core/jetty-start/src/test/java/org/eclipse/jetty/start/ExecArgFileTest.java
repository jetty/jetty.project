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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code java @argument-file} used to launch the forked (second) JVM, which keeps
 * the child's configuration out of the visible process arguments (issue #14005).
 */
public class ExecArgFileTest
{
    private static final String SECRET = "S3cr3t_14005_doNotLeak";

    private StartArgs newStartArgs(Main main, Path base, String... extra) throws Exception
    {
        Path home = MavenPaths.findTestResourceDir("dist-home");
        Files.createDirectories(base);
        List<String> args = new ArrayList<>();
        args.add("jetty.home=" + home);
        args.add("jetty.base=" + base);
        for (String e : extra)
            args.add(e);
        return main.processCommandLine(args.toArray(new String[0]));
    }

    @Test
    public void testArgFileHoldsSecretButCommandLineDoesNot() throws Exception
    {
        Path base = MavenPaths.targetTestDir("exec-argfile-secret");
        FS.ensureEmpty(base);
        Main main = new Main();
        StartArgs args = newStartArgs(main, base);

        // Stand in for the forked command: a JVM option and a secret-bearing property value.
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("-Xmx512m");
        cmd.addArg("my.secret.marker", SECRET);

        Path argFile = main.writeExecArgFile(args, cmd);

        // created in ${jetty.base} by default
        assertThat(argFile.toRealPath().getParent(), is(base.toRealPath()));
        assertTrue(Files.isRegularFile(argFile));

        // restrictive 0600 where the filesystem supports POSIX permissions
        if (Files.getFileStore(argFile).supportsFileAttributeView(PosixFileAttributeView.class))
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(argFile)), is("rw-------"));

        // the secret IS in the argfile ...
        assertThat(Files.readString(argFile), containsString(SECRET));

        // ... but the launch command line is only "java @argfile" and never names the secret
        List<String> launch = List.of(CommandLineBuilder.findJavaBin(), "@" + argFile.toAbsolutePath());
        assertThat(launch, not(hasItem(containsString(SECRET))));
        assertThat(launch.get(1), is("@" + argFile.toAbsolutePath()));
    }

    @Test
    public void testArgFileDirectoryDefaultsToJettyBase() throws Exception
    {
        Path base = MavenPaths.targetTestDir("exec-argfile-default-dir");
        FS.ensureEmpty(base);
        Main main = new Main();
        StartArgs args = newStartArgs(main, base);
        assertThat(main.resolveExecArgFileDir(args).toRealPath(), is(base.toRealPath()));
    }

    @Test
    public void testArgFileDirectoryFromServerTempDirectory() throws Exception
    {
        Path base = MavenPaths.targetTestDir("exec-argfile-temp-dir");
        FS.ensureEmpty(base);
        Path temp = base.resolve("server-temp");
        Files.createDirectories(temp);
        Main main = new Main();
        StartArgs args = newStartArgs(main, base, "jetty.server.tempDirectory=" + temp);
        assertThat(main.resolveExecArgFileDir(args).toRealPath(), is(temp.toRealPath()));
    }
}
