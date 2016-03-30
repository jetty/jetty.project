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

package org.eclipse.jetty.http;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class QuotedCSVTest
{
    @Test
    public void testOWS()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("  value 0.5  ;  p = v  ;  q =0.5  ,  value 1.0 ");
        Assert.assertThat(values,Matchers.contains(
                "value 0.5;p=v;q=0.5",
                "value 1.0"));
    }
    
    @Test
    public void testEmpty()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue(",aaaa,  , bbbb ,,cccc,");
        Assert.assertThat(values,Matchers.contains(
                "aaaa",
                "bbbb",
                "cccc"));
    }
        
    @Test
    public void testQuoted()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("A;p=\"v\",B,\"C, D\"");
        Assert.assertThat(values,Matchers.contains(
                "A;p=\"v\"",
                "B",
                "\"C, D\""));
    }
    
    @Test
    public void testOpenQuote()
    {
        QuotedCSV values = new QuotedCSV();
        values.addValue("value;p=\"v");
        Assert.assertThat(values,Matchers.contains(
                "value;p=\"v"));
    }
    
    @Test
    public void testQuotedNoQuotes()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("A;p=\"v\",B,\"C, D\"");
        Assert.assertThat(values,Matchers.contains(
                "A;p=v",
                "B",
                "C, D"));
    }
    
    @Test
    public void testOpenQuoteNoQuotes()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("value;p=\"v");
        assertThat(values,Matchers.contains(
                "value;p=v"));
    }

    @Test
    public void testUnQuote()
    {
        assertThat(QuotedCSV.unquote(""),is(""));
        assertThat(QuotedCSV.unquote("\"\""),is(""));
        assertThat(QuotedCSV.unquote("foo"),is("foo"));
        assertThat(QuotedCSV.unquote("\"foo\""),is("foo"));
        assertThat(QuotedCSV.unquote("f\"o\"o"),is("foo"));
        assertThat(QuotedCSV.unquote("\"\\\"foo\""),is("\"foo"));
        assertThat(QuotedCSV.unquote("\\foo"),is("\\foo"));
    }
    
}
