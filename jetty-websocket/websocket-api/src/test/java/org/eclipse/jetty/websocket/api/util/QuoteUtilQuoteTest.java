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

package org.eclipse.jetty.websocket.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test QuoteUtil.quote(), and QuoteUtil.dequote()
 */
public class QuoteUtilQuoteTest
{
    public static Stream<Arguments> data()
    {
        // The various quoting of a String
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[]{"Hi", "\"Hi\""});
        data.add(new Object[]{"Hello World", "\"Hello World\""});
        data.add(new Object[]{"9.0.0", "\"9.0.0\""});
        data.add(new Object[]{
            "Something \"Special\"",
            "\"Something \\\"Special\\\"\""
        });
        data.add(new Object[]{
            "A Few\n\"Good\"\tMen",
            "\"A Few\\n\\\"Good\\\"\\tMen\""
        });

        return data.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDequoting(final String unquoted, final String quoted)
    {
        String actual = QuoteUtil.dequote(quoted);
        actual = QuoteUtil.unescape(actual);
        assertThat(actual, is(unquoted));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testQuoting(final String unquoted, final String quoted)
    {
        StringBuilder buf = new StringBuilder();
        QuoteUtil.quote(buf, unquoted);

        String actual = buf.toString();
        assertThat(actual, is(quoted));
    }
}
