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
        values.addValue("  value 0.5  ;  pqy = vwz  ;  q =0.5  ,  value 1.0 ,  other ; param ");
        Assert.assertThat(values,Matchers.contains(
                "value 0.5;pqy=vwz;q=0.5",
                "value 1.0",
                "other;param"));
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
    public void testParamsOnly()
    {
        QuotedCSV values = new QuotedCSV(false);
        values.addValue("for=192.0.2.43, for=\"[2001:db8:cafe::17]\", for=unknown");
        assertThat(values,Matchers.contains(
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
                    String s = buffer.toString().replace("DELETE","");
                    buffer.setLength(0);
                    buffer.append(s);
                }
                if (buffer.toString().contains("APPEND"))
                {
                    String s = buffer.toString().replace("APPEND","Append")+"!";
                    buffer.setLength(0);
                    buffer.append(s);
                }
            }

            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                String name = paramValue>0?buffer.substring(paramName,paramValue-1):buffer.substring(paramName);
                if ("IGNORE".equals(name))
                    buffer.setLength(paramName-1);
            }
            
        };
            
        values.addValue("normal;param=val, testAPPENDandDELETEvalue ; n=v; IGNORE = this; x=y ");
        assertThat(values,Matchers.contains(
                "normal;param=val",
                "testAppendandvalue!;n=v;x=y"));
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
