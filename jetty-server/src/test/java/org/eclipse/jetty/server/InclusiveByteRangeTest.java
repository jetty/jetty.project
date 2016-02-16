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

package org.eclipse.jetty.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Vector;

import org.junit.Test;

public class InclusiveByteRangeTest
{
    @SuppressWarnings("unchecked")
    private void assertInvalidRange(String rangeString)
    {
        Vector strings = new Vector();
        strings.add(rangeString);

        List ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),200);
        assertNull("Invalid Range [" + rangeString + "] should result in no satisfiable ranges",ranges);
    }

    private void assertRange(String msg, int expectedFirst, int expectedLast, int size, InclusiveByteRange actualRange)
    {
        assertEquals(msg + " - first",expectedFirst,actualRange.getFirst(size));
        assertEquals(msg + " - last",expectedLast,actualRange.getLast(size));
        String expectedHeader = String.format("bytes %d-%d/%d",expectedFirst,expectedLast,size);
        assertEquals(msg + " - header range string",expectedHeader,actualRange.toHeaderRangeString(size));
    }

    private void assertSimpleRange(int expectedFirst, int expectedLast, String rangeId, int size)
    {
        InclusiveByteRange range = parseRange(rangeId,size);

        assertEquals("Range [" + rangeId + "] - first",expectedFirst,range.getFirst(size));
        assertEquals("Range [" + rangeId + "] - last",expectedLast,range.getLast(size));
        String expectedHeader = String.format("bytes %d-%d/%d",expectedFirst,expectedLast,size);
        assertEquals("Range [" + rangeId + "] - header range string",expectedHeader,range.toHeaderRangeString(size));
    }

    @SuppressWarnings("unchecked")
    private InclusiveByteRange parseRange(String rangeString, int size)
    {
        Vector strings = new Vector();
        strings.add(rangeString);

        List ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),size);
        assertNotNull("Satisfiable Ranges should not be null",ranges);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",1,ranges.size());
        return (InclusiveByteRange)ranges.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<InclusiveByteRange> parseRanges(String rangeString, int size)
    {
        Vector strings = new Vector();
        strings.add(rangeString);

        List<InclusiveByteRange> ranges;
        ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),size);
        assertNotNull("Satisfiable Ranges should not be null",ranges);
        return ranges;
    }

    @Test
    public void testHeader416RangeString()
    {
        assertEquals("416 Header on size 100","bytes */100",InclusiveByteRange.to416HeaderRangeString(100));
        assertEquals("416 Header on size 123456789","bytes */123456789",InclusiveByteRange.to416HeaderRangeString(123456789));
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

        List<InclusiveByteRange> ranges = parseRanges(rangeString,size);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        assertRange("Range [" + rangeString + "]",5,20,size,ranges.get(0));
        assertRange("Range [" + rangeString + "]",35,49,size,ranges.get(1));
    }

    /**
     * Range definition has a range that is clipped due to the size.
     */
    @Test
    public void testMultipleRangesClipped()
    {
        String rangeString;

        rangeString = "bytes=5-20,35-65,-5";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,50);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",3,ranges.size());
        assertRange("Range [" + rangeString + "]",5,20,50,ranges.get(0));
        assertRange("Range [" + rangeString + "]",35,49,50,ranges.get(1));
        assertRange("Range [" + rangeString + "]",45,49,50,ranges.get(2));
    }

    @Test
    public void testMultipleRangesOverlapping()
    {
        String rangeString;

        rangeString = "bytes=5-20,15-25";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,200);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        assertRange("Range [" + rangeString + "]",5,20,200,ranges.get(0));
        assertRange("Range [" + rangeString + "]",15,25,200,ranges.get(1));
    }

    @Test
    public void testMultipleRangesSplit()
    {
        String rangeString;
        rangeString = "bytes=5-10,15-20";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,200);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        assertRange("Range [" + rangeString + "]",5,10,200,ranges.get(0));
        assertRange("Range [" + rangeString + "]",15,20,200,ranges.get(1));
    }

    @Test
    public void testSimpleRange()
    {
        assertSimpleRange(5,10,"bytes=5-10",200);
        assertSimpleRange(195,199,"bytes=-5",200);
        assertSimpleRange(50,119,"bytes=50-150",120);
        assertSimpleRange(50,119,"bytes=50-",120);
    }
}
