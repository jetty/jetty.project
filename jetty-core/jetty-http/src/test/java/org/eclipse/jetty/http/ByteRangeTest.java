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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ByteRangeTest
{
    private void assertInvalidRange(String rangeString)
    {
        List<String> strings = List.of(rangeString);
        List<ByteRange> ranges = ByteRange.parse(strings, 200);
        assertNotNull(ranges);
        assertThat("Invalid Range [" + rangeString + "] should result in no satisfiable ranges", ranges.size(), is(0));
    }

    private void assertRange(String msg, int expectedFirst, int expectedLast, int size, ByteRange actualRange)
    {
        assertEquals(expectedFirst, actualRange.first(), msg + " - first");
        assertEquals(expectedLast, actualRange.last(), msg + " - last");
        String expectedHeader = String.format("bytes %d-%d/%d", expectedFirst, expectedLast, size);
        assertThat(msg + " - header range string", actualRange.toHeaderValue(size), is(expectedHeader));
    }

    private void assertSimpleRange(int expectedFirst, int expectedLast, String rangeId, int size)
    {
        ByteRange range = parseRange(rangeId, size);

        assertEquals(expectedFirst, range.first(), "Range [" + rangeId + "] - first");
        assertEquals(expectedLast, range.last(), "Range [" + rangeId + "] - last");
        String expectedHeader = String.format("bytes %d-%d/%d", expectedFirst, expectedLast, size);
        assertEquals(expectedHeader, range.toHeaderValue(size), "Range [" + rangeId + "] - header range string");
    }

    private ByteRange parseRange(String rangeString, int size)
    {
        List<String> strings = List.of(rangeString);
        List<ByteRange> ranges = ByteRange.parse(strings, size);
        assertNotNull(ranges, "Satisfiable Ranges should not be null");
        assertEquals(1, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        return ranges.get(0);
    }

    private List<ByteRange> parseRanges(int size, String... rangeString)
    {
        List<String> strings = List.of(rangeString);
        List<ByteRange> ranges = ByteRange.parse(strings, size);
        assertNotNull(ranges, "Satisfiable Ranges should not be null");
        return ranges;
    }

    @Test
    public void testHeader416RangeString()
    {
        assertEquals("bytes */100", ByteRange.toNonSatisfiableHeaderValue(100), "416 Header on size 100");
        assertEquals("bytes */123456789", ByteRange.toNonSatisfiableHeaderValue(123456789), "416 Header on size 123456789");
    }

    @Test
    public void testInvalidRanges()
    {
        // Invalid if parsing "Range" header
        assertInvalidRange("bytes=a-b"); // letters are invalid
        assertInvalidRange("byte=10-3"); // unit is bad and values wrong
        assertInvalidRange("onceuponatime=5-10"); // unit is bad
        assertInvalidRange("bytes=300-310"); // outside of size (200)
    }

    /**
     * Ranges have a multiple ranges, all absolutely defined.
     */
    @Test
    public void testMultipleAbsoluteRanges()
    {
        int size = 50;
        String rangeString = "bytes=5-20,35-65";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 5, 20, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 35, 49, size, ranges.get(1));
    }

    /**
     * Ranges have a multiple ranges, all absolutely defined.
     */
    @Test
    public void testMultipleAbsoluteRangesSplit()
    {
        int size = 50;

        List<ByteRange> ranges = parseRanges(size, "bytes=5-20", "bytes=35-65");
        assertEquals(2, ranges.size());
        assertRange("testMultipleAbsoluteRangesSplit[0]", 5, 20, size, ranges.get(0));
        assertRange("testMultipleAbsoluteRangesSplit[1]", 35, 49, size, ranges.get(1));
    }

    /**
     * Range definition has a range that is clipped due to the size.
     */
    @Test
    public void testMultipleRangesClipped()
    {
        int size = 50;
        String rangeString = "bytes=5-20,35-65,-5";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 5, 20, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 35, 49, size, ranges.get(1));
    }

    @Test
    public void testMultipleRangesOverlapping()
    {
        int size = 200;
        String rangeString = "bytes=5-20,15-25";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(1, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 5, 25, size, ranges.get(0));
    }

    @Test
    public void testMultipleRangesSplit()
    {
        int size = 200;
        String rangeString = "bytes=5-10,15-20";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 5, 10, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 15, 20, size, ranges.get(1));
    }

    @Test
    public void testMultipleSameRangesSplit()
    {
        int size = 200;
        String rangeString = "bytes=5-10,15-20,5-10,15-20,5-10,5-10,5-10,5-10,5-10,5-10";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 5, 10, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 15, 20, size, ranges.get(1));
    }

    @Test
    public void testMultipleOverlappingRanges()
    {
        int size = 200;
        String rangeString = "bytes=5-15,20-30,10-25";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(1, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 5, 30, size, ranges.get(0));
    }

    @Test
    public void testMultipleOverlappingRangesOrdered()
    {
        int size = 200;
        String rangeString = "bytes=20-30,5-15,0-5,25-35";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 0, 15, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 20, 35, size, ranges.get(1));
    }

    @Test
    public void testMultipleOverlappingRangesOrderedSplit()
    {
        int size = 200;
        String rangeString = "bytes=20-30,5-15,0-5,25-35";
        List<ByteRange> ranges = parseRanges(size, "bytes=20-30", "bytes=5-15", "bytes=0-5,25-35");

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 0, 15, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 20, 35, size, ranges.get(1));
    }

    @Test
    public void testNasty()
    {
        int size = 200;
        String rangeString = "bytes=90-100, 10-20, 30-40, -161";
        List<ByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        assertRange("Range [" + rangeString + "]", 10, 20, size, ranges.get(0));
        assertRange("Range [" + rangeString + "]", 30, 199, size, ranges.get(1));
    }

    @Test
    public void testRangeOpenEnded()
    {
        assertSimpleRange(50, 499, "bytes=50-", 500);
    }

    @Test
    public void testSimpleRange()
    {
        assertSimpleRange(5, 10, "bytes=5-10", 200);
        assertSimpleRange(195, 199, "bytes=-5", 200);
        assertSimpleRange(50, 119, "bytes=50-150", 120);
        assertSimpleRange(50, 119, "bytes=50-", 120);

        assertSimpleRange(1, 50, "bytes= 1 - 50", 120);
    }

    // TODO: evaluate this vs assertInvalidRange() above, which behavior is correct? null? or empty list?
    private void assertBadRangeList(int size, String badRange)
    {
        List<String> strings = List.of(badRange);
        List<ByteRange> ranges = ByteRange.parse(strings, size);
        // If one part is bad, the entire set of ranges should be treated as bad, per RFC7233.
        assertNotNull(ranges);
        assertThat("Should have no ranges", ranges.size(), is(0));
    }

    @Test
    public void testBadRangeSetPartiallyBad()
    {
        assertBadRangeList(500, "bytes=1-50,1-b,a-50");
    }

    @Test
    public void testBadRangeNoNumbers()
    {
        assertBadRangeList(500, "bytes=a-b");
    }

    @Test
    public void testBadRangeEmpty()
    {
        assertBadRangeList(500, "bytes=");
    }

    @Test
    public void testBadRangeHex()
    {
        assertBadRangeList(500, "bytes=0F-FF");
    }

    @Test
    public void testBadRangeTabDelim()
    {
        assertBadRangeList(500, "bytes=1-50\t90-101\t200-250");
    }

    @Test
    public void testBadRangeSemiColonDelim()
    {
        assertBadRangeList(500, "bytes=1-50;90-101;200-250");
    }

    @Test
    public void testBadRangeNegativeSize()
    {
        assertBadRangeList(500, "bytes=50-1");
    }

    @Test
    public void testBadRangeDoubleDash()
    {
        assertBadRangeList(500, "bytes=1--20");
    }

    @Test
    public void testBadRangeTripleDash()
    {
        assertBadRangeList(500, "bytes=1---");
    }

    @Test
    public void testBadRangeZeroedNegativeSize()
    {
        assertBadRangeList(500, "bytes=050-001");
    }
}
