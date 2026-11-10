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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
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
        cmd.addArg("-Djetty.home", "/opt/jetty 12/home");
        assertThat(cmd.toCommandLine(), is("java -Djetty.home='/opt/jetty 12/home'"));
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

    public static Stream<Arguments> argFileQuoting()
    {
        return Stream.of(
            // null and empty
            Arguments.of(null, null),
            Arguments.of("", "\"\""),
            // nothing special -> left as-is
            Arguments.of("Hello", "Hello"),
            Arguments.of("H-llo_world", "H-llo_world"),
            Arguments.of("/opt/jetty", "/opt/jetty"),
            Arguments.of("monetary.symbol=€", "monetary.symbol=€"),
            // a backslash with no whitespace is literal outside quotes, so no quoting needed
            Arguments.of("C:\\jetty\\home", "C:\\jetty\\home"),
            // whitespace -> quoted
            Arguments.of("Hello World", "\"Hello World\""),
            Arguments.of("-Djetty.home=/opt/jetty 12/home", "\"-Djetty.home=/opt/jetty 12/home\""),
            // tab is a separator -> quoted (literal tab preserved inside quotes)
            Arguments.of("foo\tbar", "\"foo\tbar\""),
            // '#' would start a comment outside quotes -> quoted
            Arguments.of("a#b", "\"a#b\""),
            // a single quote would open a quote outside quotes -> quoted, literal inside double quotes
            Arguments.of("O'Brien", "\"O'Brien\""),
            // a double quote must be quoted and escaped
            Arguments.of("say \"hi\"", "\"say \\\"hi\\\"\""),
            // Windows path with a space: quoted, and backslashes doubled inside quotes
            Arguments.of("C:\\Program Files\\jetty", "\"C:\\\\Program Files\\\\jetty\""),
            // raw newline/carriage-return must be escaped (a raw newline ends a token even inside quotes)
            Arguments.of("a\nb", "\"a\\nb\""),
            Arguments.of("a\rb", "\"a\\rb\""),
            // form-feed is a separator -> quoted; it is preserved literally inside double quotes
            // (verified against the JDK launcher, which keeps a literal 0x0C as part of the token)
            Arguments.of("a\fb", "\"a\fb\""),
            // a value mixing space, '#', both quote kinds and a backslash: everything in one token
            // (round-trips through the real java launcher back to the original value)
            Arguments.of("a b#'\"\\", "\"a b#'\\\"\\\\\"")
        );
    }

    @ParameterizedTest
    @MethodSource("argFileQuoting")
    public void testArgFileQuoting(String string, String expected)
    {
        assertThat(CommandLineBuilder.argFileQuoteIfNeeded(string), is(expected));
    }

    @Test
    public void testWriteArgLines()
    {
        CommandLineBuilder cmd = new CommandLineBuilder();
        cmd.addArg("-Djetty.home", "/opt/jetty 12/home");
        cmd.addOption("--module");
        cmd.addArg("org.eclipse.jetty.xml.XmlConfiguration");

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bout, true, UTF_8))
        {
            cmd.writeArgLines(out);
        }

        String[] lines = bout.toString(UTF_8).split("\\R");
        assertThat(lines.length, is(3));
        // one token per line; the token with a space is quoted
        assertThat(lines[0], is("\"-Djetty.home=/opt/jetty 12/home\""));
        assertThat(lines[1], is("--module"));
        assertThat(lines[2], is("org.eclipse.jetty.xml.XmlConfiguration"));
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
