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

package org.eclipse.jetty.util;

import static org.junit.Assert.*;

import org.junit.Assert;
import org.junit.Test;

public class SearchPatternTest
{

    @Test
    public void testBasicSearch()
    {
        String p1 = "truth";
        String p2 = "evident";
        String p3 = "we";
        String d = "we hold these truths to be self evident";
        
        
        // Testing Compiled Pattern p1 "truth"
        SearchPattern sp1 = SearchPattern.compile(p1.getBytes());
        Assert.assertEquals(14,sp1.match(d.getBytes(), 0, d.length()));        
        Assert.assertEquals(14,sp1.match(d.getBytes(),14,p1.length()));        
        Assert.assertEquals(14,sp1.match(d.getBytes(),14,p1.length()+1));
        Assert.assertEquals(-1,sp1.match(d.getBytes(),14,p1.length()-1));        
        Assert.assertEquals(-1,sp1.match(d.getBytes(),15,d.length()));
        
        // Testing Compiled Pattern p2 "evident"
        SearchPattern sp2 = SearchPattern.compile(p2.getBytes());        
        Assert.assertEquals(32,sp2.match(d.getBytes(), 0, d.length()));        
        Assert.assertEquals(32,sp2.match(d.getBytes(),32,p2.length()));        
        Assert.assertEquals(32,sp2.match(d.getBytes(),32,p2.length()+1));        
        Assert.assertEquals(-1,sp2.match(d.getBytes(),32,p2.length()-1));        
        Assert.assertEquals(-1,sp2.match(d.getBytes(),33,d.length()));
        
        
        // Testing Compiled Pattern p3 "evident"
        SearchPattern sp3 = SearchPattern.compile(p3.getBytes());   
        Assert.assertEquals( 0,sp3.match(d.getBytes(), 0, d.length()));
        Assert.assertEquals( 0,sp3.match(d.getBytes(), 0, p3.length()));
        Assert.assertEquals( 0,sp3.match(d.getBytes(), 0, p3.length()+1));
        Assert.assertEquals(-1,sp3.match(d.getBytes(), 0, p3.length()-1));
        Assert.assertEquals(-1,sp3.match(d.getBytes(), 1, d.length()));
        
    }
    
    
    @Test
    public void testDoubleMatch()
    {
        String p = "violent";
        String d = "These violent delights have violent ends.";
        
        // Testing Compiled Pattern p1 "truth"
        SearchPattern sp = SearchPattern.compile(p.getBytes());
        Assert.assertEquals( 6,sp.match(d.getBytes(), 0, d.length()));
        Assert.assertEquals(-1,sp.match(d.getBytes(), 6, p.length()-1));
        Assert.assertEquals(28,sp.match(d.getBytes(), 7, d.length()));
        Assert.assertEquals(28,sp.match(d.getBytes(), 28, d.length()));
        Assert.assertEquals(-1,sp.match(d.getBytes(), 29, d.length()));
        
    }

}
