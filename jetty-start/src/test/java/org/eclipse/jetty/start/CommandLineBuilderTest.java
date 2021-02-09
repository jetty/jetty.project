//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
