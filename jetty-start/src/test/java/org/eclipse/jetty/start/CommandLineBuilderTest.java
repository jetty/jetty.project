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

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CommandLineBuilderTest
{
    @Test
    public void testSimpleCommandline()
    {
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("java");
        cmd.addArg("-Djava.io.tmpdir", "/home/java/temp dir/");
        cmd.addArg("--version");
        assertThat(cmd.toCommandLine(), is("java -Djava.io.tmpdir='/home/java/temp dir/' --version"));
    }

    @Test
    public void testSimpleHomeNoSpace()
    {
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("java");
        cmd.addArg("-Djetty.home", "/opt/jetty");
        assertThat(cmd.toCommandLine(), is("java -Djetty.home=/opt/jetty"));
    }

    @Test
    public void testSimpleHomeWithSpace()
    {
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("java");
        cmd.addArg("-Djetty.home", "/opt/jetty 10/home");
        assertThat(cmd.toCommandLine(), is("java -Djetty.home='/opt/jetty 10/home'"));
    }

    @Test
    public void testEscapedFormattingString()
    {
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("java");
        cmd.addArg("-Djetty.home", "/opt/jetty");
        cmd.addArg("jetty.requestlog.formatter", "%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t \"%r\" %s %O \"%{Referer}i\" \"%{User-Agent}i\"");
        assertThat(cmd.toCommandLine(), is("java -Djetty.home=/opt/jetty jetty.requestlog.formatter='%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t \"%r\" %s %O \"%{Referer}i\" \"%{User-Agent}i\"'"));
    }

    @Test
    public void testEscapeUnicode()
    {
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("java");
        cmd.addArg("-Djetty.home", "/opt/jetty");
        cmd.addArg("monetary.symbol", "€");
        assertThat(cmd.toCommandLine(), is("java -Djetty.home=/opt/jetty monetary.symbol='€'"));
    }

    public static Stream<Arguments> shellQuoting()
    {
        return Stream.of(
            Arguments.of(null, null),
            Arguments.of("", "''"),
            Arguments.of("Hello", "Hello"),
            Arguments.of("Hell0", "Hell0"),
            Arguments.of("Hello$World", "'Hello$World'"),
            Arguments.of("Hello\\World", "'Hello\\World'"),
            Arguments.of("Hello`World", "'Hello`World'"),
            Arguments.of("'Hello World'", "\\''Hello World'\\'"),
            Arguments.of("\"Hello World\"", "'\"Hello World\"'"),
            Arguments.of("H-llo_world", "H-llo_world"),
            Arguments.of("H:llo/world", "H:llo/world"),
            Arguments.of("Hello World", "'Hello World'"),
            Arguments.of("foo\\bar", "'foo\\bar'"),
            Arguments.of("foo'bar", "'foo'\\''bar'"),
            Arguments.of("some 'internal' quoting", "'some '\\''internal'\\'' quoting'"),
            Arguments.of("monetary.symbol=€", "'monetary.symbol=€'")
        );
    }

    @ParameterizedTest
    @MethodSource("shellQuoting")
    public void testShellQuoting(String string, String expected)
    {
        assertThat(CommandLineBuilder.shellQuoteIfNeeded(string), is(expected));
    }

    @Test
    public void testMultiLine()
    {
        CommandLineBuilder cmd = new CommandLineBuilder(true);
        cmd.addArg("java");
        cmd.addArg("-Djetty.home", "/opt/jetty");
        cmd.addArg("monetary.symbol", "€");
        assertThat(cmd.toCommandLine(),
            is("java \\" + System.lineSeparator() +
                "  -Djetty.home=/opt/jetty \\" + System.lineSeparator() +
                "  monetary.symbol='€'"));
    }
}
