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
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code java @argument-file} used to launch the forked (second) JVM, which keeps
 * the child's configuration out of the visible process arguments.
 */
public class ExecArgFileTest
{
    private static final String SECRET = "S3cr3t_doNotLeak";

    @Test
    public void testArgFileHoldsSecretButCommandLineDoesNot() throws Exception
    {
        Main main = new Main();

        // Stand in for the forked command: a JVM option and a secret-bearing property value.
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("-Xmx512m");
        cmd.addArg("my.secret.marker", SECRET);

        Path argFile = main.writeExecArgFile(cmd);
        try
        {
            // created in java.io.tmpdir (a per-user temp directory on Windows)
            assertThat(argFile.toRealPath().getParent(),
                is(Paths.get(System.getProperty("java.io.tmpdir")).toRealPath()));
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
        finally
        {
            Files.deleteIfExists(argFile);
        }
    }
}
