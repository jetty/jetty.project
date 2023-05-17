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

package org.eclipse.jetty.util;

import java.util.Iterator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class QuotedStringTokenizerLegacyTest
{
    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer0()
    {
        TestTokenizer tok =
            new TestTokenizer("abc\n\"d\\\"'\"\n'p\\',y'\nz", null, false, false, true);
        checkTok(tok, false, false);
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer1()
    {
        TestTokenizer tok =
            new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,", false, false, true);
        checkTok(tok, false, false);
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer2()
    {
        TestTokenizer tok =
            new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,", false, false, true);
        checkTok(tok, false, false);

        tok = new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,", true, false, true);
        checkTok(tok, true, false);
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer3()
    {
        TestTokenizer tok;

        tok = new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
            false, false, true);
        checkTok(tok, false, false);

        tok = new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
            false, true, true);
        checkTok(tok, false, true);

        tok = new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
            true, false, true);
        checkTok(tok, true, false);

        tok = new TestTokenizer("abc, \"d\\\"'\",'p\\',y' z", " ,",
            true, true, true);
        checkTok(tok, true, true);
    }

    @Test
    public void testQuote()
    {
        QuotedStringTokenizer tokenizer = QuotedStringTokenizer.builder().legacy().build();
        StringBuffer buf = new StringBuffer();

        buf.setLength(0);
        tokenizer.quote(buf, "abc \n efg");
        assertEquals("\"abc \\n efg\"", buf.toString());

        buf.setLength(0);
        tokenizer.quote(buf, "abcefg");
        assertEquals("\"abcefg\"", buf.toString());

        buf.setLength(0);
        tokenizer.quote(buf, "abcefg\"");
        assertEquals("\"abcefg\\\"\"", buf.toString());
    }

    /*
     * Test for String nextToken()
     */
    @Test
    public void testTokenizer4()
    {
        TestTokenizer tok = new TestTokenizer("abc'def,ghi'jkl", ",", false, false, false);
        Iterator<String> iter = tok.test();
        assertEquals("abc'def", iter.next());
        assertEquals("ghi'jkl", iter.next());
        tok = new TestTokenizer("abc'def,ghi'jkl", ",", false, false, true);
        iter = tok.test();
        assertEquals("abcdef,ghijkl", iter.next());
    }

    private void checkTok(TestTokenizer tok, boolean delim, boolean quotes)
    {
        Iterator<String> trial = tok.test();
        assertTrue(trial.hasNext());
        assertEquals("abc", trial.next());
        if (delim)
            assertEquals(",", trial.next());
        if (delim)
            assertEquals(" ", trial.next());

        assertEquals(quotes ? "\"d\\\"'\"" : "d\"'", trial.next());
        if (delim)
            assertEquals(",", trial.next());
        assertEquals(quotes ? "'p\\',y'" : "p',y", trial.next());
        if (delim)
            assertEquals(" ", trial.next());
        assertEquals("z", trial.next());
        assertFalse(trial.hasNext());
    }

    /*
     * Test for String quote(String, String)
     */
    @Test
    public void testQuoteIfNeeded()
    {
        QuotedStringTokenizer tokenizer = QuotedStringTokenizer.builder().legacy().delimiters(" ,").build();
        assertEquals("abc", tokenizer.quoteIfNeeded("abc"));
        assertEquals("\"a c\"", tokenizer.quoteIfNeeded("a c"));
        assertEquals("\"a'c\"", tokenizer.quoteIfNeeded("a'c"));
        assertEquals("\"a\\n\\r\\t\"", tokenizer.quote("a\n\r\t"));
        assertEquals("\"\\u0000\\u001f\"", tokenizer.quote("\u0000\u001f"));
    }

    @Test
    public void testUnquote()
    {
        QuotedStringTokenizer tokenizer = QuotedStringTokenizer.builder().legacy().delimiters(" ,").build();
        assertEquals("abc", tokenizer.unquote("abc"));
        assertEquals("a\"c", tokenizer.unquote("\"a\\\"c\""));
        assertEquals("a'c", tokenizer.unquote("\"a'c\""));
        assertEquals("a\n\r\t", tokenizer.unquote("\"a\\n\\r\\t\""));
        assertEquals("\u0000\u001f ", tokenizer.unquote("\"\u0000\u001f\u0020\""));
        assertEquals("\u0000\u001f ", tokenizer.unquote("\"\u0000\u001f\u0020\""));
        assertEquals("ab\u001ec", tokenizer.unquote("ab\u001ec"));
        assertEquals("ab\u001ec", tokenizer.unquote("\"ab\u001ec\""));
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
        String contentDisposition = "form-data; name=\"fileup\"; filename=\"Taken on Aug 22 \\ 2012.jpg\"";

        TestTokenizer tok = new TestTokenizer(contentDisposition, ";", false, true, true);
        Iterator<String> trial = tok.test();

        assertEquals("form-data", trial.next().trim());
        assertEquals("name=\"fileup\"", trial.next().trim());
        assertEquals("filename=\"Taken on Aug 22 \\ 2012.jpg\"", trial.next().trim());
    }
    
    static class TestTokenizer
    {
        private final String _string;
        private final QuotedStringTokenizer _tokenizer;
        
        public TestTokenizer(String string, String delimiters, boolean returnDelimiters, boolean returnQuotes, boolean singleQuotes)
        {
            _string = string;
            QuotedStringTokenizer.Builder builder = QuotedStringTokenizer.builder().legacy();
            if (delimiters != null)
                builder.delimiters(delimiters);
            if (returnDelimiters)
                builder.returnDelimiters();
            if (returnQuotes)
                builder.returnQuotes();
            if (singleQuotes)
                builder.allowSingleQuotes();
            _tokenizer = builder.build();
        }

        Iterator<String> test()
        {
            return _tokenizer.tokenize(_string);
        }
    }
}

