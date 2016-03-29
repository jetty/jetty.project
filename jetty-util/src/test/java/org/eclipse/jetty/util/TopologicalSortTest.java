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

import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.*;

import java.util.Arrays;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class TopologicalSortTest
{

    @Test
    public void testNoDependencies()
    {
        String[] s = { "D","E","C","B","A" };
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.sort(s);
        
        Assert.assertThat(s,Matchers.arrayContaining("D","E","C","B","A"));        
    }
    
    @Test
    public void testSimpleLinear()
    {
        String[] s = { "D","E","C","B","A" };
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("B","A");
        ts.addDependency("C","B");
        ts.addDependency("D","C");
        ts.addDependency("E","D");
        
        ts.sort(s);
        
        Assert.assertThat(s,Matchers.arrayContaining("A","B","C","D","E"));        
    }

    @Test
    public void testDisjoint()
    {
        String[] s = { "A","C","B","CC","AA","BB"};
        
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("B","A");
        ts.addDependency("C","B");
        ts.addDependency("BB","AA");
        ts.addDependency("CC","BB");
        
        ts.sort(s);
        
        Assert.assertThat(s,Matchers.arrayContaining("A","B","C","AA","BB","CC"));   
    }

    @Test
    public void testDisjointReversed()
    {
        String[] s = { "CC","AA","BB","A","C","B"};
        
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("B","A");
        ts.addDependency("C","B");
        ts.addDependency("BB","AA");
        ts.addDependency("CC","BB");
        
        ts.sort(s);
          
        Assert.assertThat(s,Matchers.arrayContaining("AA","BB","CC","A","B","C"));  
    }

    @Test
    public void testDisjointMixed()
    {
        String[] s = { "CC","A","AA","C","BB","B"};
        
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("B","A");
        ts.addDependency("C","B");
        ts.addDependency("BB","AA");
        ts.addDependency("CC","BB");
        
        ts.sort(s);

        // Check direct ordering
        Assert.assertThat(indexOf(s,"A"),lessThan(indexOf(s,"B"))); 
        Assert.assertThat(indexOf(s,"B"),lessThan(indexOf(s,"C"))); 
        Assert.assertThat(indexOf(s,"AA"),lessThan(indexOf(s,"BB"))); 
        Assert.assertThat(indexOf(s,"BB"),lessThan(indexOf(s,"CC"))); 
    }

    @Test
    public void testTree()
    {
        String[] s = { "LeafA0","LeafB0","LeafA1","Root","BranchA","LeafB1","BranchB"};
        
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("BranchB","Root");
        ts.addDependency("BranchA","Root");
        ts.addDependency("LeafA1","BranchA");
        ts.addDependency("LeafA0","BranchA");
        ts.addDependency("LeafB0","BranchB");
        ts.addDependency("LeafB1","BranchB");
        
        ts.sort(s);
        
        // Check direct ordering
        Assert.assertThat(indexOf(s,"Root"),lessThan(indexOf(s,"BranchA")));  
        Assert.assertThat(indexOf(s,"Root"),lessThan(indexOf(s,"BranchB")));   
        Assert.assertThat(indexOf(s,"BranchA"),lessThan(indexOf(s,"LeafA0")));    
        Assert.assertThat(indexOf(s,"BranchA"),lessThan(indexOf(s,"LeafA1"))); 
        Assert.assertThat(indexOf(s,"BranchB"),lessThan(indexOf(s,"LeafB0")));    
        Assert.assertThat(indexOf(s,"BranchB"),lessThan(indexOf(s,"LeafB1")));   
        
        // check remnant ordering of original list
        Assert.assertThat(indexOf(s,"BranchA"),lessThan(indexOf(s,"BranchB")));  
        Assert.assertThat(indexOf(s,"LeafA0"),lessThan(indexOf(s,"LeafA1")));  
        Assert.assertThat(indexOf(s,"LeafB0"),lessThan(indexOf(s,"LeafB1")));  
    }

    @Test
    public void testPreserveOrder()
    {
        String[] s = { "Deep","Foobar","Wibble","Bozo","XXX","12345","Test"};
        
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("Deep","Test");
        ts.addDependency("Deep","Wibble");
        ts.addDependency("Deep","12345");
        ts.addDependency("Deep","XXX");
        ts.addDependency("Deep","Foobar");
        ts.addDependency("Deep","Bozo");
        
        ts.sort(s);
        Assert.assertThat(s,Matchers.arrayContaining("Foobar","Wibble","Bozo","XXX","12345","Test","Deep")); 
    }
    
    @Test
    public void testSimpleLoop()
    {
        String[] s = { "A","B","C","D","E" };
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("B","A");
        ts.addDependency("A","B");
        
        try
        {
            ts.sort(s);
            Assert.fail();
        }
        catch(IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),Matchers.containsString("cyclic"));
        }
    }

    @Test
    public void testDeepLoop()
    {
        String[] s = { "A","B","C","D","E" };
        TopologicalSort<String> ts = new TopologicalSort<>();
        ts.addDependency("B","A");
        ts.addDependency("C","B");
        ts.addDependency("D","C");
        ts.addDependency("E","D");
        ts.addDependency("A","E");
        try
        {
            ts.sort(s);
            Assert.fail();
        }
        catch(IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),Matchers.containsString("cyclic"));
        }
    }
    
    private int indexOf(String[] list,String s)
    {
        for (int i=0;i<list.length;i++)
            if (list[i]==s)
                return i;
        return -1;
    }
}
