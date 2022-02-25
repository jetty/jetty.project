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

package org.eclipse.jetty.http;

import java.util.Collections;

import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class QuotedCSVTest
{
    @Test
    public void testOWS()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("  value 0.5  ;  pqy = vwz  ;  q =0.5  ,  value 1.0 ,  other ; param ");
        assertThat(values, Matchers.contains(
            "value 0.5;pqy=vwz;q=0.5",
            "value 1.0",
            "other;param"));
    }

    @Test
    public void testEmpty()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue(",aaaa,  , bbbb ,,cccc,");
        assertThat(values, Matchers.contains(
            "aaaa",
            "bbbb",
            "cccc"));
    }

    @Test
    public void testQuoted()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("A;p=\"v\",B,\"C, D\"");
        assertThat(values, Matchers.contains(
            "A;p=\"v\"",
            "B",
            "\"C, D\""));
    }

    @Test
    public void testOpenQuote()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("value;p=\"v");
        assertThat(values, Matchers.contains(
            "value;p=\"v"));
    }

    @Test
    public void testQuotedNoQuotes()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("A;p=\"v\",B,\"C, D\"");
        assertThat(values, Matchers.contains(
            "A;p=v",
            "B",
            "C, D"));
    }

    @Test
    public void testOpenQuoteNoQuotes()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("value;p=\"v");
        assertThat(values, Matchers.contains(
            "value;p=v"));
    }

    @Test
    public void testParamsOnly()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("for=192.0.2.43, for=\"[2001:db8:cafe::17]\", for=unknown");
        assertThat(values, Matchers.contains(
            "for=192.0.2.43",
            "for=[2001:db8:cafe::17]",
            "for=unknown"));
    }

    @Test
    public void testMutation()
    {
        QuotedCSV values = new QuotedCSV(false)
        {

            @Override
            protected void parsedValue(StringBuffer buffer)
            {
                if (buffer.toString().contains("DELETE"))
                {
                    String s = StringUtil.strip(buffer.toString(), "DELETE");
                    buffer.setLength(0);
                    buffer.append(s);
                }
                if (buffer.toString().contains("APPEND"))
                {
                    String s = StringUtil.replace(buffer.toString(), "APPEND", "Append") + "!";
                    buffer.setLength(0);
                    buffer.append(s);
                }
            }

            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                String name = paramValue > 0 ? buffer.substring(paramName, paramValue - 1) : buffer.substring(paramName);
                if ("IGNORE".equals(name))
                    buffer.setLength(paramName - 1);
            }
        };

        values.addValue("normal;param=val, testAPPENDandDELETEvalue ; n=v; IGNORE = this; x=y ");
        assertThat(values, Matchers.contains(
            "normal;param=val",
            "testAppendandvalue!;n=v;x=y"));
    }

    @Test
    public void testUnQuote()
    {
        assertThat(QuotedCSV.unquote(""), is(""));
        assertThat(QuotedCSV.unquote("\"\""), is(""));
        assertThat(QuotedCSV.unquote("foo"), is("foo"));
        assertThat(QuotedCSV.unquote("\"foo\""), is("foo"));
        assertThat(QuotedCSV.unquote("f\"o\"o"), is("foo"));
        assertThat(QuotedCSV.unquote("\"\\\"foo\""), is("\"foo"));
        assertThat(QuotedCSV.unquote("\\foo"), is("\\foo"));
    }

    @Test
    public void testJoin()
    {
        assertThat(QuotedCSV.join((String)null), nullValue());
        assertThat(QuotedCSV.join(Collections.emptyList()), is(emptyString()));
        assertThat(QuotedCSV.join(Collections.singletonList("hi")), is("hi"));
        assertThat(QuotedCSV.join("hi", "ho"), is("hi, ho"));
        assertThat(QuotedCSV.join("h i", "h,o"), is("\"h i\", \"h,o\""));
        assertThat(QuotedCSV.join("h\"i", "h\to"), is("\"h\\\"i\", \"h\\to\""));
    }
}
