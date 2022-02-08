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

package org.eclipse.jetty.util.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class PasswordTest
{
    @Test
    public void testDeobfuscate()
    {
        // check any changes do not break already encoded strings
        String password = "secret password !# ";
        String obfuscate = "OBF:1iaa1g3l1fb51i351sw01ym91hdc1yt41v1p1ym71v2p1yti1hhq1ym51svy1hyl1f7h1fzx1i5o";
        assertEquals(password, Password.deobfuscate(obfuscate));
    }

    @Test
    public void testObfuscate()
    {
        String password = "secret password !# ";
        String obfuscate = Password.obfuscate(password);
        assertEquals(password, Password.deobfuscate(obfuscate));
    }

    @Test
    public void testObfuscateUnicode()
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        String password = "secret password !#\u20ac ";
        String obfuscate = Password.obfuscate(password);
        assertEquals(password, Password.deobfuscate(obfuscate));
    }

    @Test
    public void testCommandLineUsage() throws IOException, InterruptedException
    {
        ProcessBuilder passwordBuilder = new ProcessBuilder()
            .directory(MavenTestingUtils.getTargetDir())
            .command("java",
                "-cp", MavenTestingUtils.getTargetPath("classes").toString(),
                Password.class.getName(),
                "user", "password")
            .redirectErrorStream(true);

        Process passwordProcess = passwordBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(passwordProcess.getInputStream())))
        {
            String output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            if (passwordProcess.waitFor(5, TimeUnit.SECONDS))
            {
                int exitCode = passwordProcess.exitValue();
                assertThat("Non-error exit code: " + output, exitCode, is(0));
                assertThat("Output", output, not(containsString("Exception")));
                assertThat("Output", output, allOf(
                    containsString("password"),
                    containsString("OBF:"),
                    containsString("MD5:"),
                    containsString("CRYPT:")
                ));
            }
            else
            {
                System.out.println(output);
                passwordProcess.destroy();
                fail("Process didn't exit properly (was forcibly destroyed)");
            }
        }
    }
}
