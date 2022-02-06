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
    private CommandLineBuilder cmd = new CommandLineBuilder("java");

    @BeforeEach
    public void setUp()
    {
        cmd.addEqualsArg("-Djava.io.tmpdir", "/home/java/temp dir/");
        cmd.addArg("--version");
    }

    @Test
    public void testSimpleCommandline()
    {
        assertThat(cmd.toString(), is("java -Djava.io.tmpdir=/home/java/temp\\ dir/ --version"));
    }

    @Test
    public void testQuotingSimple()
    {
        assertQuoting("/opt/jetty", "/opt/jetty");
    }

    @Test
    public void testQuotingSpaceInPath()
    {
        assertQuoting("/opt/jetty 7/home", "/opt/jetty\\ 7/home");
    }

    @Test
    public void testQuotingSpaceAndQuotesInPath()
    {
        assertQuoting("/opt/jetty 7 \"special\"/home", "/opt/jetty\\ 7\\ \\\"special\\\"/home");
    }

    @Test
    public void testToStringIsQuotedEvenIfArgsAreNotQuotedForProcessBuilder()
    {
        System.out.println(cmd.toString());
    }

    @Test
    public void testQuoteQuotationMarks()
    {
        assertQuoting("-XX:OnOutOfMemoryError='kill -9 %p'", "-XX:OnOutOfMemoryError='kill -9 %p'");
    }

    private void assertQuoting(String raw, String expected)
    {
        String actual = CommandLineBuilder.quote(raw);
        assertThat("Quoted version of [" + raw + "]", actual, is(expected));
    }
}
