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
import static org.junit.Assert.assertThat;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.junit.Assert;
import org.junit.Test;

/**
 * Test QuoteUtil
 */
public class QuoteUtilTest
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
        Iterator<String> iter = QuoteUtil.splitAt("permessage-compress; method=\"foo, bar\"",";");
        assertSplitAt(iter,"permessage-compress","method=\"foo, bar\"");
    }

    @Test
    public void testSplitAt_PreserveQuotingWithNestedDelim()
    {
        Iterator<String> iter = QuoteUtil.splitAt("permessage-compress; method=\"foo; x=10\"",";");
        assertSplitAt(iter,"permessage-compress","method=\"foo; x=10\"");
    }

    @Test(expected = NoSuchElementException.class)
    public void testSplitAtAllWhitespace()
    {
        Iterator<String> iter = QuoteUtil.splitAt("   ","=");
        Assert.assertThat("Has Next",iter.hasNext(),is(false));
        iter.next(); // should trigger NoSuchElementException
    }

    @Test(expected = NoSuchElementException.class)
    public void testSplitAtEmpty()
    {
        Iterator<String> iter = QuoteUtil.splitAt("","=");
        Assert.assertThat("Has Next",iter.hasNext(),is(false));
        iter.next(); // should trigger NoSuchElementException
    }

    @Test
    public void testSplitAtHelloWorld()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Hello World"," =");
        assertSplitAt(iter,"Hello","World");
    }

    @Test
    public void testSplitAtKeyValue_Message()
    {
        Iterator<String> iter = QuoteUtil.splitAt("method=\"foo, bar\"","=");
        assertSplitAt(iter,"method","foo, bar");
    }

    @Test
    public void testSplitAtQuotedDelim()
    {
        // test that split ignores delimiters that occur within a quoted
        // part of the sequence.
        Iterator<String> iter = QuoteUtil.splitAt("A,\"B,C\",D",",");
        assertSplitAt(iter,"A","B,C","D");
    }

    @Test
    public void testSplitAtSimple()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Hi","=");
        assertSplitAt(iter,"Hi");
    }

    @Test
    public void testSplitKeyValue_Quoted()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Key = \"Value\"","=");
        assertSplitAt(iter,"Key","Value");
    }

    @Test
    public void testSplitKeyValue_QuotedValueList()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Fruit = \"Apple, Banana, Cherry\"","=");
        assertSplitAt(iter,"Fruit","Apple, Banana, Cherry");
    }

    @Test
    public void testSplitKeyValue_QuotedWithDelim()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Key = \"Option=Value\"","=");
        assertSplitAt(iter,"Key","Option=Value");
    }

    @Test
    public void testSplitKeyValue_Simple()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Key=Value","=");
        assertSplitAt(iter,"Key","Value");
    }

    @Test
    public void testSplitKeyValue_WithWhitespace()
    {
        Iterator<String> iter = QuoteUtil.splitAt("Key = Value","=");
        assertSplitAt(iter,"Key","Value");
    }
    
    @Test
    public void testQuoteIfNeeded()
    {
        StringBuilder buf = new StringBuilder();
        QuoteUtil.quoteIfNeeded(buf, "key",",");
        assertThat("key",buf.toString(),is("key"));
    }
    
    @Test
    public void testQuoteIfNeeded_null()
    {
        StringBuilder buf = new StringBuilder();
        QuoteUtil.quoteIfNeeded(buf, null,";=");
        assertThat("<null>",buf.toString(),is(""));
    }
}
