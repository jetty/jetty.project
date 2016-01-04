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

package org.eclipse.jetty.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


/**
 *
 *
 */
public class QuotedStringTokenizerTest
{
    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer0()
    {
        QuotedStringTokenizer tok =
            new QuotedStringTokenizer("abc\n\"d\\\"'\"\n'p\\',y'\nz");
        checkTok(tok,false,false);
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer1()
    {
        QuotedStringTokenizer tok =
            new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z",
                                      " ,");
        checkTok(tok,false,false);
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer2()
    {
        QuotedStringTokenizer tok =
            new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
            false);
        checkTok(tok,false,false);

        tok = new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
                                        true);
        checkTok(tok,true,false);
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer3()
    {
        QuotedStringTokenizer tok;

        tok = new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
                                        false,false);
        checkTok(tok,false,false);

        tok = new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
                                        false,true);
        checkTok(tok,false,true);

        tok = new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
                                        true,false);
        checkTok(tok,true,false);

        tok = new QuotedStringTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
                                        true,true);
        checkTok(tok,true,true);
    }

    @Test
    public void testQuote()
    {
        StringBuffer buf = new StringBuffer();

        buf.setLength(0);
        QuotedStringTokenizer.quote(buf,"abc \n efg");
        assertEquals("\"abc \\n efg\"",buf.toString());

        buf.setLength(0);
        QuotedStringTokenizer.quote(buf,"abcefg");
        assertEquals("\"abcefg\"",buf.toString());

        buf.setLength(0);
        QuotedStringTokenizer.quote(buf,"abcefg\"");
        assertEquals("\"abcefg\\\"\"",buf.toString());

    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer4()
    {
        QuotedStringTokenizer tok = new QuotedStringTokenizer("abc'def,ghi'jkl",",");
        tok.setSingle(false);
        assertEquals("abc'def",tok.nextToken());
        assertEquals("ghi'jkl",tok.nextToken());
        tok = new QuotedStringTokenizer("abc'def,ghi'jkl",",");
        tok.setSingle(true);
        assertEquals("abcdef,ghijkl",tok.nextToken());
    }

    private void checkTok(QuotedStringTokenizer tok,boolean delim,boolean quotes)
    {
        assertTrue(tok.hasMoreElements());
        assertTrue(tok.hasMoreTokens());
        assertEquals("abc",tok.nextToken());
        if (delim)assertEquals(",",tok.nextToken());
        if (delim)assertEquals(" ",tok.nextToken());

        assertEquals(quotes?"\"d\\\"'\"":"d\"'",tok.nextElement());
        if (delim)assertEquals(",",tok.nextToken());
        assertEquals(quotes?"'p\\',y'":"p',y",tok.nextToken());
        if (delim)assertEquals(" ",tok.nextToken());
        assertEquals("z",tok.nextToken());
        assertFalse(tok.hasMoreTokens());
    }

    /*
     * Test for String quote(String, String)
     */
    @Test
    public void testQuoteIfNeeded()
    {
        assertEquals("abc",QuotedStringTokenizer.quoteIfNeeded("abc", " ,"));
        assertEquals("\"a c\"",QuotedStringTokenizer.quoteIfNeeded("a c", " ,"));
        assertEquals("\"a'c\"",QuotedStringTokenizer.quoteIfNeeded("a'c", " ,"));
        assertEquals("\"a\\n\\r\\t\"",QuotedStringTokenizer.quote("a\n\r\t"));
        assertEquals("\"\\u0000\\u001f\"",QuotedStringTokenizer.quote("\u0000\u001f"));
    }

    @Test
    public void testUnquote()
    {
        assertEquals("abc",QuotedStringTokenizer.unquote("abc"));
        assertEquals("a\"c",QuotedStringTokenizer.unquote("\"a\\\"c\""));
        assertEquals("a'c",QuotedStringTokenizer.unquote("\"a'c\""));
        assertEquals("a\n\r\t",QuotedStringTokenizer.unquote("\"a\\n\\r\\t\""));
        assertEquals("\u0000\u001f ",QuotedStringTokenizer.unquote("\"\u0000\u001f\u0020\""));
        assertEquals("\u0000\u001f ",QuotedStringTokenizer.unquote("\"\u0000\u001f\u0020\""));
        assertEquals("ab\u001ec",QuotedStringTokenizer.unquote("ab\u001ec"));
        assertEquals("ab\u001ec",QuotedStringTokenizer.unquote("\"ab\u001ec\""));
    }
    
    
    @Test
    public void testUnquoteOnly()
    {
        assertEquals("abc",QuotedStringTokenizer.unquoteOnly("abc"));
        assertEquals("a\"c",QuotedStringTokenizer.unquoteOnly("\"a\\\"c\""));
        assertEquals("a'c",QuotedStringTokenizer.unquoteOnly("\"a'c\""));
        assertEquals("a\\n\\r\\t",QuotedStringTokenizer.unquoteOnly("\"a\\\\n\\\\r\\\\t\""));
        assertEquals("ba\\uXXXXaaa", QuotedStringTokenizer.unquoteOnly("\"ba\\\\uXXXXaaa\""));
    }

    /**
     * When encountering a Content-Disposition line during a multi-part mime file
     * upload, the filename="..." field can contain '\' characters that do not
     * belong to a proper escaping sequence, this tests QuotedStringTokenizer to
     * ensure that it preserves those slashes for where they cannot be escaped.
     */
    @Test
    public void testNextTokenOnContentDisposition()
    {
        String content_disposition = "form-data; name=\"fileup\"; filename=\"Taken on Aug 22 \\ 2012.jpg\"";
     
        QuotedStringTokenizer tok=new QuotedStringTokenizer(content_disposition,";",false,true);
        
        assertEquals("form-data", tok.nextToken().trim());
        assertEquals("name=\"fileup\"", tok.nextToken().trim());
        assertEquals("filename=\"Taken on Aug 22 \\ 2012.jpg\"", tok.nextToken().trim());
    }
}
