//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test QuoteUtil.quote(), and QuoteUtil.dequote()
 */
@RunWith(Parameterized.class)
public class QuoteUtil_QuoteTest
{
    @Parameters
    public static Collection<Object[]> data()
    {
        // The various quoting of a String
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        data.add(new Object[] { "Hi", "\"Hi\"" });
        data.add(new Object[] { "Hello World", "\"Hello World\"" });
        data.add(new Object[] { "9.0.0", "\"9.0.0\"" });
        data.add(new Object[] { "Something \"Special\"", 
                                "\"Something \\\"Special\\\"\"" });
        data.add(new Object[] { "A Few\n\"Good\"\tMen", 
                                "\"A Few\\n\\\"Good\\\"\\tMen\"" });
        // @formatter:on

        return data;
    }

    private String unquoted;
    private String quoted;

    public QuoteUtil_QuoteTest(String unquoted, String quoted)
    {
        this.unquoted = unquoted;
        this.quoted = quoted;
    }

    @Test
    public void testDequoting()
    {
        String actual = QuoteUtil.dequote(quoted);
        actual = QuoteUtil.unescape(actual);
        Assert.assertThat(actual,is(unquoted));
    }

    @Test
    public void testQuoting()
    {
        StringBuilder buf = new StringBuilder();
        QuoteUtil.quote(buf,unquoted);

        String actual = buf.toString();
        Assert.assertThat(actual,is(quoted));
    }
}
