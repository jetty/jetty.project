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

package org.eclipse.jetty.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.junit.Ignore;
import org.junit.Test;

public class InclusiveByteRangeTest
{
    private void assertInvalidRange(String rangeString)
    {
        Vector<String> strings = new Vector<>();
        strings.add(rangeString);

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges( strings.elements(), 200);
        assertNull("Invalid Range [" + rangeString + "] should result in no satisfiable ranges",ranges);
    }

    private void assertRange(String msg, int expectedFirst, int expectedLast, int size, InclusiveByteRange actualRange)
    {
        assertEquals(msg + " - first",expectedFirst,actualRange.getFirst());
        assertEquals(msg + " - last",expectedLast,actualRange.getLast());
        String expectedHeader = String.format("bytes %d-%d/%d",expectedFirst,expectedLast,size);
        assertEquals(msg + " - header range string",expectedHeader,actualRange.toHeaderRangeString(size));
    }

    private void assertSimpleRange(int expectedFirst, int expectedLast, String rangeId, int size)
    {
        InclusiveByteRange range = parseRange(rangeId,size);

        assertEquals("Range [" + rangeId + "] - first",expectedFirst,range.getFirst());
        assertEquals("Range [" + rangeId + "] - last",expectedLast,range.getLast());
        String expectedHeader = String.format("bytes %d-%d/%d",expectedFirst,expectedLast,size);
        assertEquals("Range [" + rangeId + "] - header range string",expectedHeader,range.toHeaderRangeString(size));
    }

    private InclusiveByteRange parseRange(String rangeString, int size)
    {
        Vector<String> strings = new Vector<>();
        strings.add(rangeString);

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),size);
        assertNotNull("Satisfiable Ranges should not be null",ranges);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",1,ranges.size());
        return (InclusiveByteRange)ranges.iterator().next();
    }

    private List<InclusiveByteRange> parseRanges(int size, String... rangeString)
    {
        Vector<String> strings = new Vector<>();
        for (String range : rangeString)
            strings.add(range);

        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),size);
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

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",5,20,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",35,49,size,inclusiveByteRangeIterator.next());
    }

    /**
     * Ranges have a multiple ranges, all absolutely defined.
     */
    @Test
    public void testMultipleAbsoluteRangesSplit()
    {
        int size = 50;

        List<InclusiveByteRange> ranges = parseRanges(size,"bytes=5-20","bytes=35-65");
        assertEquals(2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("testMultipleAbsoluteRangesSplit[0]",5,20,size,inclusiveByteRangeIterator.next());
        assertRange("testMultipleAbsoluteRangesSplit[1]",35,49,size,inclusiveByteRangeIterator.next());
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

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",5,20,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",35,49,size,inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleRangesOverlapping()
    {
        int size = 200;
        String rangeString;

        rangeString = "bytes=5-20,15-25";

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",1,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",5,25,size,inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleRangesSplit()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=5-10,15-20";

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",5,10,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",15,20,size,inclusiveByteRangeIterator.next());
    }

    @Test
    public void testMultipleSameRangesSplit()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=5-10,15-20,5-10,15-20,5-10,5-10,5-10,5-10,5-10,5-10";

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",5,10,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",15,20,size,inclusiveByteRangeIterator.next());
    }
    
    @Test
    public void testMultipleOverlappingRanges()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=5-15,20-30,10-25";

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",1,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",5,30,size,inclusiveByteRangeIterator.next());
    }
    
    @Test
    public void testMultipleOverlappingRangesOrdered()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=20-30,5-15,0-5,25-35";

        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",20,35,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",0,15,size,inclusiveByteRangeIterator.next());
    }
    
    @Test
    public void testMultipleOverlappingRangesOrderedSplit()
    {
        int size = 200;
        String rangeString;
        rangeString = "bytes=20-30,5-15,0-5,25-35";
        List<InclusiveByteRange> ranges = parseRanges(size,"bytes=20-30","bytes=5-15","bytes=0-5,25-35");
        
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",20,35,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",0,15,size,inclusiveByteRangeIterator.next());
    }
    
    @Test
    public void testNasty()
    {
        int size = 200;
        String rangeString;

        rangeString = "bytes=90-100, 10-20, 30-40, -161";
        List<InclusiveByteRange> ranges = parseRanges(size,rangeString);
        
        assertEquals("Satisfiable Ranges of [" + rangeString + "] count",2,ranges.size());
        Iterator<InclusiveByteRange> inclusiveByteRangeIterator = ranges.iterator();
        assertRange("Range [" + rangeString + "]",30,199,size,inclusiveByteRangeIterator.next());
        assertRange("Range [" + rangeString + "]",10,20,size,inclusiveByteRangeIterator.next());
    }
    
    @Test
    public void testRange_OpenEnded()
    {
        assertSimpleRange(50, 499, "bytes=50-", 500);
    }
    
    @Test
    public void testSimpleRange()
    {
        assertSimpleRange(5,10,"bytes=5-10",200);
        assertSimpleRange(195,199,"bytes=-5",200);
        assertSimpleRange(50,119,"bytes=50-150",120);
        assertSimpleRange(50,119,"bytes=50-",120);
        
        assertSimpleRange(1,50,"bytes= 1 - 50",120);
    }

    // TODO: evaluate this vs assertInvalidRange() above, which behavior is correct? null? or empty list?
    private void assertBadRangeList(int size, String badRange)
    {
        Vector<String> strings = new Vector<>();
        strings.add(badRange);
    
        List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(strings.elements(),size);
        // if one part is bad, the entire set of ranges should be treated as bad, per RFC7233
        assertThat("Should have no ranges", ranges, is(nullValue()));
    }
    
    @Test
    @Ignore
    public void testBadRange_SetPartiallyBad()
    {
        assertBadRangeList(500, "bytes=1-50,1-b,a-50");
    }
    
    @Test
    public void testBadRange_NoNumbers()
    {
        assertBadRangeList(500, "bytes=a-b");
    }
    
    @Test
    public void testBadRange_Empty()
    {
        assertBadRangeList(500, "bytes=");
    }
    
    @Test
    @Ignore
    public void testBadRange_ZeroPrefixed()
    {
        assertBadRangeList(500, "bytes=01-050");
    }
    
    @Test
    public void testBadRange_Hex()
    {
        assertBadRangeList(500, "bytes=0F-FF");
    }
    
    @Test
    @Ignore
    public void testBadRange_TabWhitespace()
    {
        assertBadRangeList(500, "bytes=\t1\t-\t50");
    }
    
    @Test
    public void testBadRange_TabDelim()
    {
        assertBadRangeList(500, "bytes=1-50\t90-101\t200-250");
    }
    
    @Test
    public void testBadRange_SemiColonDelim()
    {
        assertBadRangeList(500, "bytes=1-50;90-101;200-250");
    }
    
    @Test
    public void testBadRange_NegativeSize()
    {
        assertBadRangeList(500, "bytes=50-1");
    }
    
    @Test
    public void testBadRange_DoubleDash()
    {
        assertBadRangeList(500, "bytes=1--20");
    }
    
    @Test
    public void testBadRange_TrippleDash()
    {
        assertBadRangeList(500, "bytes=1---");
    }
    
    @Test
    public void testBadRange_ZeroedNegativeSize()
    {
        assertBadRangeList(500, "bytes=050-001");
    }
}
