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

package org.eclipse.jetty.http;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpCompliance.Violation.WHITESPACE_IN_PARAMETER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class QuotedCSVTest
{
    @Test
    public void testOWS()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("  value 0.5  ;  pqy=vwz  ;  q=0.5  ,  value 1.0 ,  other ; param ; p=\"x y\" ");
        assertThat(values, Matchers.contains(
            "value 0.5;pqy=vwz;q=0.5",
            "value 1.0",
            "other;param;p=\"x y\""));
    }

    @Test
    public void testAllowedBWS()
    {
        QuotedCSV values = new QuotedCSV(true)
        {
            @Override
            protected void onComplianceViolation(ComplianceViolation violation, String value)
            {
                if (WHITESPACE_IN_PARAMETER.equals(violation))
                    return;
                super.onComplianceViolation(violation, value);
            }
        };
        values.addValue("  value 0.5  ;  pqy = vwz  ;  q =0.5  ,  value 1.0 ,  other ; param ");
        assertThat(values, Matchers.contains(
            "value 0.5;pqy=vwz;q=0.5",
            "value 1.0",
            "other;param"));
    }

    @Test
    public void testBWS()
    {
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param = value"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param =value"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param= value"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param= "));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "param = value"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param\t=\tvalue"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param\t=value"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param=\tvalue"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "value;param=\t"));
        assertThrows(IllegalArgumentException.class, () -> new QuotedCSV(true, "param\t=\tvalue"));
    }

    @Test
    public void testEmpty()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue(",aaaa,  , bbbb ,,\"\",cccc,");
        assertThat(values, Matchers.contains(
            "aaaa",
            "bbbb",
            "",
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
    public void testETag()
    {
        QuotedCSV values = new QuotedCSV.Etags(null, null, "W/\"000000000\", W/\"123456789\", W/\"999999999\"");
        assertThat(values, Matchers.contains(
            "W/\"000000000\"",
            "W/\"123456789\"",
            "W/\"999999999\""));
    }

    @Test
    public void testOpenQuote()
    {
        BadlyQuotedQuotedCSV values = new BadlyQuotedQuotedCSV();
        values.addValue("\"value\";a=1;p=\"v");
        assertThat(values.isBadlyQuoted(), is(true));
        assertThat(values, Matchers.contains("\"value\";a=1;p=\"v"));
    }

    @Test
    public void testQuotedNoQuotesWithComma()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("A;p=\"v\",B,\"C, D\"");
        assertThat(values, Matchers.contains(
            "A;p=v",
            "B",
            "C, D"));
    }

    @Test
    public void testQuotedNoQuotesWithSemicolon()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("A;p=\"v\",B,\"C; D\"");
        assertThat(values, Matchers.contains(
            "A;p=v",
            "B",
            "C; D"));
    }

    @Test
    public void testOpenQuoteNoQuotes()
    {
        BadlyQuotedQuotedCSV values = new BadlyQuotedQuotedCSV(false);
        values.addValue("value;p=\"v");
        assertThat(values, Matchers.contains("value;p=v"));
        assertThat(values.isBadlyQuoted(), is(true));
    }

    @Test
    public void testListParamedWithNoParamedOnly()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("for=192.0.2.60; proto=http;by=203.0.113.43, for=192.0.2.43");
        String[] expected = {
            "for=192.0.2.60;proto=http;by=203.0.113.43",
            "for=192.0.2.43"
        };
        assertThat(values, contains(expected));
    }

    @Test
    public void testListNoParamedWithParamedOnly()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("for=192.0.2.43, for=192.0.2.60;proto=http;by=203.0.113.43");
        String[] expected = {
            "for=192.0.2.43",
            "for=192.0.2.60;proto=http;by=203.0.113.43"
        };
        assertThat(values, contains(expected));
    }

    @Test
    public void testListForwardedRules()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("for=192.0.2.43, for=\"[2001:db8:cafe::17]\", for=unknown");
        String[] expected = {
            "for=192.0.2.43",
            "for=[2001:db8:cafe::17]",
            "for=unknown"
        };
        assertThat(values, contains(expected));
    }

    /**
     * When parsing a value with a parameter, the parameter should be preserved.
     * This is what we would see if using QuotedCSV with parsing a request 'Cookie' header
     * (which uses `;` to separate cookies instead of `,`)
     * Eg: The HttpFields.getValueList("Cookie") should return the entire Cookie header.
     */
    @Test
    public void testValueWithParams()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("foo=bar; name=zed; b=j");
        assertEquals(1, values.size());
        String result = values.iterator().next();
        assertThat(result, is("foo=bar;name=zed;b=j"));
    }

    /**
     * When parsing a value with a parameter, the parameter should be preserved.
     */
    @Test
    public void testListValueWithParams()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("foo=bar; name=zed; b=j, color=red; type");
        String[] expected = {
            "foo=bar;name=zed;b=j",
            "color=red;type"
        };
        assertThat(values, contains(expected));
    }

    @Test
    public void testMutation()
    {
        QuotedCSV values = new QuotedCSV(false)
        {

            @Override
            protected void parsedValue(StringBuilder buffer)
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
            protected void parsedParam(StringBuilder buffer, int valueLength, int paramName, int paramValue)
            {
                String name = paramValue > 0 ? buffer.substring(paramName, paramValue - 1) : buffer.substring(paramName);
                if ("IGNORE".equals(name))
                    buffer.setLength(paramName - 1);
            }
        };

        values.addValue("normal;param=val, testAPPENDandDELETEvalue ; n=v; IGNORE=this; x=y ");
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
        assertThat(QuotedCSV.unquote("f\"o\"o"), is("f\"o\"o"));
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
        assertThat(QuotedCSV.join("h\"i", "h\to"), is("\"h\\\"i\", \"h\to\""));
    }

    private static class BadlyQuotedQuotedCSV extends QuotedCSV
    {
        private final AtomicBoolean _badQuotes = new AtomicBoolean();

        public BadlyQuotedQuotedCSV()
        {
            this(true);
        }

        public BadlyQuotedQuotedCSV(boolean keepQuotes)
        {
            super(keepQuotes);
        }

        public boolean isBadlyQuoted()
        {
            return _badQuotes.get();
        }

        @Override
        protected void onComplianceViolation(ComplianceViolation violation, String value)
        {
            if (violation == HttpCompliance.Violation.BAD_QUOTES_IN_TOKEN)
                _badQuotes.set(true);
            else
                super.onComplianceViolation(violation, value);
        }
    }
}
