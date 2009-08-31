// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.test;

import java.util.List;

import junit.framework.Assert;
import junit.framework.AssertionFailedError;

public class StringAssert
{
    /**
     * Asserts that the string (<code>haystack</code>) starts with the string (
     * <code>expected</code>)
     * 
     * @param msg
     *            the assertion message
     * @param haystack
     *            the text to search in
     * @param expected
     *            the expected starts with text
     */
    public static void assertStartsWith(String msg, String haystack, String expected)
    {
        Assert.assertNotNull(msg + ": haystack should not be null",haystack);
        Assert.assertNotNull(msg + ": expected should not be null",expected);

        if (!haystack.startsWith(expected))
        {
            StringBuffer buf = new StringBuffer();
            buf.append(msg).append(": String \"");
            int len = Math.min(expected.length() + 4,haystack.length());
            buf.append(haystack.substring(0,len));
            buf.append("\" does not start with expected \"").append(expected).append("\"");
            System.err.println(buf);
            throw new AssertionFailedError(buf.toString());
        }
    }

    /**
     * Asserts that string (<code>haystack</code>) contains specified text (
     * <code>needle</code>).
     * 
     * @param msg
     *            the assertion message
     * @param haystack
     *            the text to search in
     * @param needle
     *            the text to search for
     */
    public static void assertContains(String msg, String haystack, String needle)
    {
        Assert.assertNotNull(msg + ": haystack should not be null",haystack);
        Assert.assertNotNull(msg + ": needle should not be null",needle);

        int idx = haystack.indexOf(needle);
        if (idx == (-1))
        {
            StringBuffer buf = new StringBuffer();
            buf.append(msg).append(": ");
            buf.append("Unable to find \"").append(needle).append("\" in \"");
            buf.append(haystack).append("\"");
            System.err.println(buf);
            throw new AssertionFailedError(buf.toString());
        }
    }

    /**
     * Asserts that string (<code>haystack</code>) contains specified text (
     * <code>needle</code>), starting at offset (in <code>haystack</code>).
     * 
     * @param msg
     *            the assertion message
     * @param haystack
     *            the text to search in
     * @param needle
     *            the text to search for
     * @param offset
     *            the offset in (haystack) to perform search from
     */
    public static void assertContains(String msg, String haystack, String needle, int offset)
    {
        Assert.assertNotNull(msg + ": haystack should not be null",haystack);
        Assert.assertNotNull(msg + ": needle should not be null",needle);

        int idx = haystack.indexOf(needle,offset);
        if (idx == (-1))
        {
            StringBuffer buf = new StringBuffer();
            buf.append(msg).append(": ");
            buf.append("Unable to find \"").append(needle).append("\" in \"");
            buf.append(haystack.substring(offset)).append("\"");
            System.err.println(buf);
            throw new AssertionFailedError(buf.toString());
        }
    }

    /**
     * Asserts that string (<code>haystack</code>) does <u>not</u> contain
     * specified text (<code>needle</code>).
     * 
     * @param msg
     *            the assertion message
     * @param haystack
     *            the text to search in
     * @param needle
     *            the text to search for
     */
    public static void assertNotContains(String msg, String haystack, String needle)
    {
        Assert.assertNotNull(msg + ": haystack should not be null",haystack);
        Assert.assertNotNull(msg + ": needle should not be null",needle);

        int idx = haystack.indexOf(needle);
        if (idx != (-1))
        {
            StringBuffer buf = new StringBuffer();
            buf.append(msg).append(": ");
            buf.append("Should not have found \"").append(needle).append("\" at offset ");
            buf.append(idx).append(" in \"").append(haystack).append("\"");
            System.err.println(buf);
            throw new AssertionFailedError(buf.toString());
        }
    }

    /**
     * Asserts that string (<code>haystack</code>) does <u>not</u> contain
     * specified text (<code>needle</code>), starting at offset (in
     * <code>haystack</code>).
     * 
     * @param msg
     *            the assertion message
     * @param haystack
     *            the text to search in
     * @param needle
     *            the text to search for
     * @param offset
     *            the offset in (haystack) to perform search from
     */
    public static void assertNotContains(String msg, String haystack, String needle, int offset)
    {
        Assert.assertNotNull(msg + ": haystack should not be null",haystack);
        Assert.assertNotNull(msg + ": needle should not be null",needle);

        int idx = haystack.indexOf(needle,offset);
        if (idx != (-1))
        {
            StringBuffer buf = new StringBuffer();
            buf.append(msg).append(": ");
            buf.append("Should not have found \"").append(needle).append("\" at offset ");
            buf.append(idx).append(" in \"").append(haystack.substring(offset)).append("\"");
            System.err.println(buf);
            throw new AssertionFailedError(buf.toString());
        }
    }

    /**
     * Asserts that the list of String lines contains the same lines (without a regard for the order of those lines)
     * 
     * @param msg
     *            the assertion message
     * @param linesExpected
     *            the list of expected lines
     * @param linesActual
     *            the list of actual lines
     */
    public static void assertContainsSame(String msg, List<String> linesExpected, List<String> linesActual)
    {
        Assert.assertEquals(msg + " line count",linesExpected.size(),linesActual.size());

        for (String expected : linesExpected)
        {
            Assert.assertTrue(msg + ": expecting to see line <" + expected + ">",linesActual.contains(expected));
        }
    }
}
