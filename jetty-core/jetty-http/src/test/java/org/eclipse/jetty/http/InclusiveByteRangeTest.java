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

import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class InclusiveByteRangeTest
{
    private void assertInvalidRange(String rangeString)
    {
        Vector<String> strings = new Vector<>();
        strings.add(rangeString);

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(), 200);
        assertNull(ranges, "Invalid Range [" + rangeString + "] should result in no satisfiable ranges");
    }

    private void assertRange(String msg, int expectedFirst, int expectedLast, int size, InclusiveByteRange actualRange)
    {
        assertEquals(expectedFirst, actualRange.getFirst(), msg + " - first");
        assertEquals(expectedLast, actualRange.getLast(), msg + " - last");
        String expectedHeader = String.format("bytes %d-%d/%d", expectedFirst, expectedLast, size);
        assertThat(msg + " - header range string", actualRange.toHeaderRangeString(size), is(expectedHeader));
    }

    private void assertSimpleRange(int expectedFirst, int expectedLast, String rangeId, int size)
    {
        InclusiveByteRange range = parseRange(rangeId, size);

        assertEquals(expectedFirst, range.getFirst(), "Range [" + rangeId + "] - first");
        assertEquals(expectedLast, range.getLast(), "Range [" + rangeId + "] - last");
        String expectedHeader = String.format("bytes %d-%d/%d", expectedFirst, expectedLast, size);
        assertEquals(expectedHeader, range.toHeaderRangeString(size), "Range [" + rangeId + "] - header range string");
    }

    private InclusiveByteRange parseRange(String rangeString, int size)
    {
        Vector<String> strings = new Vector<>();
        strings.add(rangeString);

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(), size);
        assertNotNull(ranges, "Satisfiable Ranges should not be null");
        assertEquals(1, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        return ranges.iterator().next();
    }

    private List<InclusiveByteRange> parseRanges(int size, String... rangeString)
    {
        Vector<String> strings = new Vector<>();
        for (String range : rangeString)
        {
            strings.add(range);
        }

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(), size);
        assertNotNull(ranges, "Satisfiable Ranges should not be null");
        return ranges;
    }

    @Test
    public void testHeader416RangeString()
    {
        assertEquals("bytes */100", InclusiveByteRange.to416HeaderRangeString(100), "416 Header on size 100");
        assertEquals("bytes */123456789", InclusiveByteRange.to416HeaderRangeString(123456789), "416 Header on size 123456789");
    }

    @Test
    public void testInvalidRanges()
    {
        // Invalid if parsing "Range" header
        assertInvalidRange("bytes=a-b"); // letters invalid
        assertInvalidRange("byte=10-3"); // key is bad
        assertInvalidRange("onceuponatime=5-10"); // key is bad
        assertInvalidRange("bytes=300-310"); // outside of size (200)
    }

    /**
     * Ranges have a multiple ranges, all absolutely defined.
     */
    @Test
    public void testMultipleAbsoluteRanges()
    {
        int size = 50;
        String rangeString;

        rangeString = "bytes=5-20,35-65";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 5, 20, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 35, 49, size, inclusiveByteRangeIterator.next());
    }

    /**
     * Ranges have a multiple ranges, all absolutely defined.
     */
    @Test
    public void testMultipleAbsoluteRangesSplit()
    {
        int size = 50;

        List<InclusiveByteRange> ranges = parseRanges(size, "bytes=5-20", "bytes=35-65");
        assertEquals(2, ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("testMultipleAbsoluteRangesSplit[0]", 5, 20, size, inclusiveByteRangeIterator.next());
        assertRange("testMultipleAbsoluteRangesSplit[1]", 35, 49, size, inclusiveByteRangeIterator.next());
    }

    /**
     * Range definition has a range that is clipped due to the size.
     */
    @Test
    public void testMultipleRangesClipped()
    {
        int size = 50;
        String rangeString;

        rangeString = "bytes=5-20,35-65,-5";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 5, 20, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 35, 49, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleRangesOverlapping()
    {
        int size = 200;
        String rangeString;

        rangeString = "bytes=5-20,15-25";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(1, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 5, 25, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleRangesSplit()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=5-10,15-20";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 5, 10, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 15, 20, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleSameRangesSplit()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=5-10,15-20,5-10,15-20,5-10,5-10,5-10,5-10,5-10,5-10";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 5, 10, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 15, 20, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleOverlappingRanges()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=5-15,20-30,10-25";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(1, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 5, 30, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleOverlappingRangesOrdered()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=20-30,5-15,0-5,25-35";

        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);
        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 20, 35, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 0, 15, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleOverlappingRangesOrderedSplit()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=20-30,5-15,0-5,25-35";
        List<InclusiveByteRange> ranges = parseRanges(size, "bytes=20-30", "bytes=5-15", "bytes=0-5,25-35");

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 20, 35, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 0, 15, size, inclusiveByteRangeIterator.next());
    }

    @Test
    public void testNasty()
    {
        int size = 200;
        String rangeString;

        rangeString = "bytes=90-100, 10-20, 30-40, -161";
        List<InclusiveByteRange> ranges = parseRanges(size, rangeString);

        assertEquals(2, ranges.size(), "Satisfiable Ranges of [" + rangeString + "] count");
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]", 30, 199, size, inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]", 10, 20, size, inclusiveByteRangeIterator.next());
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
        Vector<String> strings = new Vector<>();
        strings.add(badRange);

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(), size);
        // if one part is bad, the entire set of ranges should be treated as bad, per RFC7233
        assertThat("Should have no ranges", ranges, is(nullValue()));
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
    public void testBadRangeTrippleDash()
    {
        assertBadRangeList(500, "bytes=1---");
    }

    @Test
    public void testBadRangeZeroedNegativeSize()
    {
        assertBadRangeList(500, "bytes=050-001");
    }
}
