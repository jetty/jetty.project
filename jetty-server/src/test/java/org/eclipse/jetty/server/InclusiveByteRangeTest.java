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

package org.eclipse.jetty.server;

import java.util.List;
import java.util.Vector;

import junit.framework.TestCase;

public class InclusiveByteRangeTest extends TestCase
{
    @SuppressWarnings("unchecked")
    private void assertInvalidRange(String rangeString, boolean allowRelativeRange)
    {
        Vector strings = new Vector();
        strings.add(rangeString);

        List ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),allowRelativeRange,200);
        assertNull("Invalid Range [" + rangeString + "] should result in no satisfiable ranges",ranges);
    }
    
    private void assertRange(String msg, int expectedFirst, int expectedLast, int size, InclusiveByteRange actualRange)
    {
        assertEquals(msg + " - first",expectedFirst,actualRange.getFirst(size));
        assertEquals(msg + " - last",expectedLast,actualRange.getLast(size));
        String expectedHeader = String.format("bytes %d-%d/%d",expectedFirst,expectedLast,size);
        assertEquals(msg + " - header range string",expectedHeader,actualRange.toHeaderRangeString(size));
    }

    private void assertSimpleRange(int expectedFirst, int expectedLast, String rangeId, int size, boolean allowRelativeRanges)
    {
        InclusiveByteRange range = parseRange(rangeId,size,allowRelativeRanges);

        assertEquals("Range [" + rangeId + "] - first",expectedFirst,range.getFirst(size));
        assertEquals("Range [" + rangeId + "] - last",expectedLast,range.getLast(size));
        String expectedHeader = String.format("bytes %d-%d/%d",expectedFirst,expectedLast,size);
        assertEquals("Range [" + rangeId + "] - header range string",expectedHeader,range.toHeaderRangeString(size));
    }

    @SuppressWarnings("unchecked")
    private InclusiveByteRange parseRange(String rangeString, int size, boolean allowRelativeRange)
    {
        Vector strings = new Vector();
        strings.add(rangeString);

        List ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),allowRelativeRange,size);
        assertNotNull("Satisfiable Ranges should not be null",ranges);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",1,ranges.size());
        return (InclusiveByteRange)ranges.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<InclusiveByteRange> parseRanges(String rangeString, int size, boolean allowRelativeRange)
    {
        Vector strings = new Vector();
        strings.add(rangeString);

        List<InclusiveByteRange> ranges;
        ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),allowRelativeRange,size);
        assertNotNull("Satisfiable Ranges should not be null",ranges);
        return ranges;
    }
    
    public void testHeader416RangeString()
    {
        assertEquals("416 Header on size 100","bytes */100",InclusiveByteRange.to416HeaderRangeString(100));
        assertEquals("416 Header on size 123456789","bytes */123456789",InclusiveByteRange.to416HeaderRangeString(123456789));
    }
    
    public void testInvalidRanges()
    {
        boolean relativeRange = true;

        // Invalid if parsing "Range" header
        assertInvalidRange("bytes=a-b",relativeRange); // letters invalid
        assertInvalidRange("byte=10-3",relativeRange); // key is bad
        assertInvalidRange("onceuponatime=5-10",relativeRange); // key is bad
        assertInvalidRange("bytes=300-310",relativeRange); // outside of size (200) 
        
        // Invalid if parsing "Content-Range" header 
        relativeRange = false;
        assertInvalidRange("bytes=-5",relativeRange); // relative from end
        assertInvalidRange("bytes=10-",relativeRange); // relative from start
        assertInvalidRange("bytes=250-300",relativeRange); // outside of size (200)
    }

    /**
     * Ranges have a multiple ranges, all absolutely defined.
     */
    public void testMultipleAbsoluteRanges()
    {
        boolean relativeRange = false;
        int size = 50;
        String rangeString;

        rangeString = "bytes=5-20,35-65";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,size,relativeRange);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        assertRange("Range [" + rangeString + "]",5,20,size,ranges.get(0));
        assertRange("Range [" + rangeString + "]",35,49,size,ranges.get(1));
    }

    /**
     * Range definition has a range that is clipped due to the size.
     */
    public void testMultipleRangesClipped()
    {
        String rangeString;

        rangeString = "bytes=5-20,35-65,-5";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,50,true);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",3,ranges.size());
        assertRange("Range [" + rangeString + "]",5,20,50,ranges.get(0));
        assertRange("Range [" + rangeString + "]",35,49,50,ranges.get(1));
        assertRange("Range [" + rangeString + "]",45,49,50,ranges.get(2));
    }

    public void testMultipleRangesOverlapping()
    {
        String rangeString;

        rangeString = "bytes=5-20,15-25";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,200,true);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        assertRange("Range [" + rangeString + "]",5,20,200,ranges.get(0));
        assertRange("Range [" + rangeString + "]",15,25,200,ranges.get(1));
    }

    public void testMultipleRangesSplit()
    {
        String rangeString;

        rangeString = "bytes=5-10,15-20";

        List<InclusiveByteRange> ranges = parseRanges(rangeString,200,true);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        assertRange("Range [" + rangeString + "]",5,10,200,ranges.get(0));
        assertRange("Range [" + rangeString + "]",15,20,200,ranges.get(1));
    }

    public void testSimpleRange()
    {
        boolean relativeRange = true;

        assertSimpleRange(5,10,"bytes=5-10",200,relativeRange);
        assertSimpleRange(195,199,"bytes=-5",200,relativeRange);
        assertSimpleRange(50,119,"bytes=50-150",120,relativeRange);
        assertSimpleRange(50,119,"bytes=50-",120,relativeRange);
    }

}
