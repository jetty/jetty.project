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

import org.junit.jupiter.api.BeforeEach;
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
        assertThat(cmd.toString(), is("java -Djava.io.tmpdir=/home/java/temp\\ dir/ --version"));
    }

    @Test
    public void testEscapedSimple()
    {
        assertEscaping("/opt/jetty", "/opt/jetty");
    }

    @Test
    public void testEscapedSpaceInPath()
    {
        assertEscaping("/opt/jetty 7/home", "/opt/jetty\\ 7/home");
    }

    @Test
    public void testEscapedSpaceAndQuotesInPath()
    {
        assertEscaping("/opt/jetty 7 \"special\"/home", "/opt/jetty\\ 7\\ \\\"special\\\"/home");
    }

    @Test
    public void testEscapedFormattingString()
    {
        assertEscaping("%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t \"%r\" %s %O \"%{Referer}i\" \"%{User-Agent}i\"",
            "\\%\\{client\\}a\\ -\\ \\%u\\ \\%\\{dd/MMM/yyyy:HH:mm:ss\\ ZZZ\\|GMT\\}t\\ \\\"\\%r\\\"\\ \\%s\\ \\%O\\ \\\"\\%\\{Referer\\}i\\\"\\ \\\"\\%\\{User-Agent\\}i\\\"");
    }

    @Test
    public void testEscapeQuotationMarks()
    {
        assertEscaping("-XX:OnOutOfMemoryError='kill -9 %p'", "-XX:OnOutOfMemoryError='kill -9 %p'");
    }

    private void assertEscaping(String raw, String expected)
    {
        String actual = CommandLineBuilder.escape(raw);
        assertThat("Escaped version of [" + raw + "]", actual, is(expected));
    }
}
