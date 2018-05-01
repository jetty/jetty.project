//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.util;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests of new QuotedCSV to make sure it retains support for the oddities see in the WebSocket ABNF.
 * <p>
 *     Historically speaking, jetty-util had a QuotedStringTokenizer that worked great with HTTP header values.
 *     However, the QuotedStringTokenizer was designed with the RFC-2616 (HTTP/1.1 obsolete) ABNF, which proved to be
 *     insufficient for the new RFC-6455 (websocket) ABNF requirements.
 *     A new QuoteUtil object was created in jetty-websocket around Jetty 9.0 to handle for this difference.
 *     Over time, the HTTP/1.1 spec evolved, the HTTPbis group got most (not all) of their ABNF changes into
 *     the updated HTTP/1.1 spec RFC-7230.
 *     Around this time, a new jetty-util replacement called QuotedCSV was introduced to handle some of
 *     these differences in the HTTP side.
 * </p>
 * <p>
 *     Other complications, the use of jetty-util classes directly in the WebAppContext classloader
 *     proved complicated and it was easier to just keep the QuoteUtil class in a
 *     org.eclipse.jetty.websocket package space to avoid this.
 * </p>
 * <p>
 *     The improvements made for WebAppContext and its classloader means that the improved
 *     QuotedCSV is now a possible candidate for replacement of the QuoteUtil class.
 *     This testcase attempts to ensure that the new QuotedCSV class can handle the
 *     scenarios seen in common WebSocket usage.
 * </p>
 */
public class QuotedCSVWebSocketTest
{
    private void assertSplitAt(Iterator<String> iter, String... expectedParts)
    {
        int len = expectedParts.length;
        for (int i = 0; i < len; i++)
        {
            String expected = expectedParts[i];
            Assert.assertThat("Split[" + i + "].hasNext()",iter.hasNext(),is(true));
            Assert.assertThat("Split[" + i + "].next()",iter.next(),is(expected));
        }
    }

    @Test
    public void testSplitAt_PreserveQuoting()
    {
        // TODO (gregw) QuotedCSV is being used incorrectly.  Test should be: 
        QuotedCSV csv = new QuotedCSV(false,"permessage-compress; method=\"foo, bar\"")
        {
            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                Assert.assertEquals("permessage-compress",buffer.substring(0,valueLength));
                Assert.assertEquals("method",buffer.substring(paramName,paramValue-1));
                Assert.assertEquals("foo, bar",buffer.substring(paramValue));
            }
        };
        
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("permessage-compress; method=\"foo, bar\"",";");
        Iterator<String> iter = new QuotedCSV(true,"permessage-compress; method=\"foo, bar\"").iterator();
        assertSplitAt(iter,"permessage-compress","method=\"foo, bar\"");
    }

    @Test
    public void testSplitAt_PreserveQuotingWithNestedDelim()
    {
        // TODO (gregw) QuotedCSV is being used incorrectly.  Test should be: 
        QuotedCSV csv = new QuotedCSV(false,"permessage-compress; method=\"foo; x=10\"")
        {
            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                Assert.assertEquals("permessage-compress",buffer.substring(0,valueLength));
                Assert.assertEquals("method",buffer.substring(paramName,paramValue-1));
                Assert.assertEquals("foo; x=10",buffer.substring(paramValue));
            }
        };
        
        
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("permessage-compress; method=\"foo; x=10\"",";");
        Iterator<String> iter = new QuotedCSV(true,"permessage-compress; method=\"foo; x=10\"").iterator();
        assertSplitAt(iter,"permessage-compress","method=\"foo; x=10\"");
    }

    @Test(expected = NoSuchElementException.class)
    public void testSplitAtAllWhitespace()
    {
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("   ","=");
        Iterator<String> iter = new QuotedCSV("   ").iterator();
        Assert.assertThat("Has Next",iter.hasNext(),is(false));
        iter.next(); // should trigger NoSuchElementException
    }

    @Test(expected = NoSuchElementException.class)
    public void testSplitAtEmpty()
    {
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("","=");
        Iterator<String> iter = new QuotedCSV("").iterator();
        Assert.assertThat("Has Next",iter.hasNext(),is(false));
        iter.next(); // should trigger NoSuchElementException
    }

    @Test
    public void testSplitAtHelloWorld()
    {
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Hello World"," =");
        
        // TODO (gregw) I think this is an incorrect test as there is only a single value with an internal space.
        // TODO (gregw) are there any concrete examples from a websocket context that need this split?
        Iterator<String> iter = new QuotedCSV("Hello World").iterator();
        assertSplitAt(iter,"Hello","World");
    }

    @Test
    public void testSplitAtKeyValue_Message()
    {
        // TODO (gregw) I think this test should be 
        QuotedCSV csv = new QuotedCSV(false,"value; param = \"foo, bar\"")
        {
            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                Assert.assertEquals("value",buffer.substring(0,valueLength));
                Assert.assertEquals("param",buffer.substring(paramName,paramValue-1));
                Assert.assertEquals("foo, bar",buffer.substring(paramValue));
            }
        };
        
        
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("method=\"foo, bar\"","=");
        Iterator<String> iter = new QuotedCSV("method=\"foo, bar\"").iterator();
        assertSplitAt(iter,"method","foo, bar");
    }

    @Test
    public void testSplitAtQuotedDelim()
    {
        // test that split ignores delimiters that occur within a quoted
        // part of the sequence.
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("A,\"B,C\",D",",");
        Iterator<String> iter = new QuotedCSV(false,"A,\"B,C\",D").iterator();
        assertSplitAt(iter,"A","B,C","D");
    }

    @Test
    public void testSplitAtSimple()
    {
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Hi","=");
        Iterator<String> iter = new QuotedCSV("Hi").iterator();
        assertSplitAt(iter,"Hi");
    }

    @Test
    public void testSplitKeyValue_Quoted()
    {        
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Key = \"Value\"","=");
        Iterator<String> iter = new QuotedCSV("Key = \"Value\"","=").iterator();
        assertSplitAt(iter,"Key","Value");
    }

    @Test
    public void testSplitKeyValue_QuotedValueList()
    {
        // TODO (gregw) Again I think this test should be for parameters not a value
        // TODO (gregw) unless you have websocket examples that show otherwise??
        QuotedCSV csv = new QuotedCSV(false,"value; Fruit = \"Apple, Banana, Cherry\"")
        {
            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                Assert.assertEquals("value",buffer.substring(0,valueLength));
                Assert.assertEquals("Fruit",buffer.substring(paramName,paramValue-1));
                Assert.assertEquals("Apple, Banana, Cherry",buffer.substring(paramValue));
            }
        };
        
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Fruit = \"Apple, Banana, Cherry\"","=");
        Iterator<String> iter = new QuotedCSV("Fruit = \"Apple, Banana, Cherry\"").iterator();
        assertSplitAt(iter,"Fruit","Apple, Banana, Cherry");
    }

    @Test
    public void testSplitKeyValue_QuotedWithDelim()
    {
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Key = \"Option=Value\"","=");
        Iterator<String> iter = new QuotedCSV("Key = \"Option=Value\"").iterator();
        assertSplitAt(iter,"Key","Option=Value");
    }

    @Test
    public void testSplitKeyValue_Simple()
    {
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Key=Value","=");
        Iterator<String> iter = new QuotedCSV("Key=Value").iterator();
        assertSplitAt(iter,"Key","Value");
    }

    @Test
    public void testSplitKeyValue_WithWhitespace()
    {
        // TODO (gregw) I think this test should be for a param and not a value
        QuotedCSV csv = new QuotedCSV(false,"value; Key = Value")
        {
            @Override
            protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
            {
                Assert.assertEquals("value",buffer.substring(0,valueLength));
                Assert.assertEquals("Key",buffer.substring(paramName,paramValue-1));
                Assert.assertEquals("Value",buffer.substring(paramValue));
            }
        };
        
        // [OLD] Iterator<String> iter = QuoteUtil.splitAt("Key = Value","=");
        Iterator<String> iter = new QuotedCSV("Key = Value").iterator();
        assertSplitAt(iter,"Key","Value");
    }

    @Test
    public void testQuoteIfNeeded()
    {
        // [OLD] StringBuilder buf = new StringBuilder();
        // [OLD] QuoteUtil.quoteIfNeeded(buf, "key",",");
        QuotedCSV csv = new QuotedCSV();
        csv.addValue("key");
        assertThat("key",csv.toString(),is("key"));
    }

    @Test
    public void testQuoteIfNeeded_null()
    {
        // [OLD] StringBuilder buf = new StringBuilder();
        // [OLD] QuoteUtil.quoteIfNeeded(buf, null,";=");
        QuotedCSV csv = new QuotedCSV();
        csv.addValue(null);
        assertThat("<null>",csv.toString(),is(""));
    }
}
