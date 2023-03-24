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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CommandLineBuilderTest
{
    @Test
    public void testSimpleCommandline()
    {
        CommandLineBuilder cmd = new CommandLineBuilder("java");
        cmd.addEqualsArg("-Djava.io.tmpdir", "/home/java/temp dir/");
        cmd.addArg("--version");
        assertThat(cmd.toQuotedString(), is("java '-Djava.io.tmpdir=/home/java/temp dir/' --version"));
    }

    @Test
    public void testSimpleHomeNoSpace()
    {
        CommandLineBuilder cmd = new CommandLineBuilder("java");
        cmd.addEqualsArg("-Djetty.home", "/opt/jetty");
        assertThat(cmd.toQuotedString(), is("java -Djetty.home=/opt/jetty"));
    }

    @Test
    public void testSimpleHomeWithSpace()
    {
        CommandLineBuilder cmd = new CommandLineBuilder("java");
        cmd.addEqualsArg("-Djetty.home", "/opt/jetty 10/home");
        assertThat(cmd.toQuotedString(), is("java '-Djetty.home=/opt/jetty 10/home'"));
    }

    @Test
    public void testEscapedFormattingString()
    {
        CommandLineBuilder cmd = new CommandLineBuilder("java");
        cmd.addEqualsArg("-Djetty.home", "/opt/jetty");
        cmd.addEqualsArg("jetty.requestlog.formatter", "%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t \"%r\" %s %O \"%{Referer}i\" \"%{User-Agent}i\"");
        assertThat(cmd.toQuotedString(), is("java -Djetty.home=/opt/jetty 'jetty.requestlog.formatter=%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t \"%r\" %s %O \"%{Referer}i\" \"%{User-Agent}i\"'"));
    }

    @Test
    public void testEscapeUnicode()
    {
        CommandLineBuilder cmd = new CommandLineBuilder("java");
        cmd.addEqualsArg("-Djetty.home", "/opt/jetty");
        cmd.addEqualsArg("monetary.symbol", "€");
        assertThat(cmd.toQuotedString(), is("java -Djetty.home=/opt/jetty monetary.symbol=€"));
    }
}
