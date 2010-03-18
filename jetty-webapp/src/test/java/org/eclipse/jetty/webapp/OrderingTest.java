// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.webapp;

import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebXmlProcessor.AbsoluteOrdering;
import org.eclipse.jetty.webapp.WebXmlProcessor.RelativeOrdering;

/**
 * OrderingTest
 *
 *
 */
public class OrderingTest extends TestCase
{
    public void testRelativeOrdering0 ()
    throws Exception
    {
        //Example from ServletSpec p.70
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new RelativeOrdering();
        
        //A: after others, after C
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        f1._hasOther=true;
        ((RelativeOrdering)processor._ordering).addAfterOthers(f1);
        f1._afters.add("C");
        
        //B: before others
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        f2._hasOther = true;
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f2);
        
        //C: after others
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        ((RelativeOrdering)processor._ordering).addAfterOthers(f3);
        
        //D: no ordering
        Fragment f4 = new Fragment((Resource)null, processor);
        f4._name="D"; 
        processor._webFragmentNameMap.put(f4._name, f4);
        ((RelativeOrdering)processor._ordering).addNoOthers(f4);
        
        //E: no ordering
        Fragment f5 = new Fragment((Resource)null, processor);
        f5._name="E"; 
        processor._webFragmentNameMap.put(f5._name, f5);
        ((RelativeOrdering)processor._ordering).addNoOthers(f5);
        
        //F: before others, before B
        Fragment f6 = new Fragment((Resource)null, processor);
        f6._name="F";
        processor._webFragmentNameMap.put(f6._name, f6);
        f6._hasOther=true;
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f6);
        f6._befores.add("B");
        
        /*
         * p.70 outcome: F, B, D, E, C, A
         */
        String[] outcomes = {"FBDECA"};
        List<Fragment> orderedList = processor._ordering.order();
        
        String result = "";
        for (Fragment f:orderedList)
            result+=(f._name);

        if (!checkResult(result, outcomes))
            fail("No outcome matched "+result);
    }
    

    
    public void testRelativeOrdering1 ()
    throws Exception
    {
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new RelativeOrdering();
        
        //Example from ServletSpec p.70-71
        //No name: after others, before C
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = Fragment.NAMELESS+"1";
        processor._webFragmentNameMap.put(f1._name, f1);
        f1._hasOther=true;
        ((RelativeOrdering)processor._ordering).addAfterOthers(f1);
        f1._befores.add("C");
        
        //B: before others
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        f2._hasOther = true;
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f2);
               
        //C: no ordering
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        ((RelativeOrdering)processor._ordering).addNoOthers(f3);
        
        //D: after others
        Fragment f4 = new Fragment((Resource)null, processor);
        f4._name="D"; 
        processor._webFragmentNameMap.put(f4._name, f4);
        f4._hasOther = true;
        ((RelativeOrdering)processor._ordering).addAfterOthers(f4);
        
        //E: before others
        Fragment f5 = new Fragment((Resource)null, processor);
        f5._name="E";
        processor._webFragmentNameMap.put(f5._name, f5);
        f5._hasOther=true; 
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f5);
        
        //F: no ordering
        Fragment f6 = new Fragment((Resource)null, processor);
        f6._name="F";
        processor._webFragmentNameMap.put(f6._name, f6);
        ((RelativeOrdering)processor._ordering).addNoOthers(f6);
        
        List<Fragment> orderedList = processor._ordering.order();

        /* p.70-71 Possible outcomes are:
         * B, E, F, noname, C, D
         * B, E, F, noname, D, C
         * E, B, F, noname, C, D
         * E, B, F, noname, D, C
         * E, B, F, D, noname, C
         */
        String[] outcomes = {"BEF"+f1._name+"CD", 
                             "BEF"+ f1._name+ "DC",
                             "EBF"+ f1._name+ "CD",
                             "EBF"+ f1._name+ "DC",
                             "EBFD"+ f1._name};
        
        String orderedNames = "";
        for (Fragment f:orderedList)
            orderedNames+=(f._name);
        
     
        if (!checkResult(orderedNames, outcomes))
            fail("No outcome matched "+orderedNames);
    }
    
    public void testRelativeOrdering2 ()
    throws Exception
    {
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new RelativeOrdering();
        
        //Example from Spec p. 71-72
        
        //A: after B
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        ((RelativeOrdering)processor._ordering).addNoOthers(f1);
        f1._afters.add("B");
        
        //B: no order
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        ((RelativeOrdering)processor._ordering).addNoOthers(f2);
        
        //C: before others
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f3);

        //D: no order
        Fragment f4 = new Fragment((Resource)null, processor);
        f4._name="D"; 
        processor._webFragmentNameMap.put(f4._name, f4);
        ((RelativeOrdering)processor._ordering).addNoOthers(f4);
        
        /*
         * p.71-72 possible outcomes are:
         * C,B,D,A
         * C,D,B,A
         * C,B,A,D
         */
        String[] outcomes = {"CBDA",
                             "CDBA",
                             "CBAD"};
       
 
        List<Fragment> orderedList = processor._ordering.order();
        String result = "";
        for (Fragment f:orderedList)
           result+=(f._name);
        
        if (!checkResult(result, outcomes))
            fail ("No outcome matched "+result);
    }
    

    public void testRelativeOrdering3 ()
    throws Exception
    { 
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new RelativeOrdering();

        //A: after others, before C
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        f1._hasOther=true;
        ((RelativeOrdering)processor._ordering).addAfterOthers(f1);
        f1._befores.add("C");

        //B: before others, before C
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        f2._hasOther = true;
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f2);
        f2._befores.add("C");

        //C: no ordering   
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        ((RelativeOrdering)processor._ordering).addNoOthers(f3);
        
        //result: BAC
        String[] outcomes = {"BAC"};
        
        List<Fragment> orderedList = processor._ordering.order();
        String result = "";
        for (Fragment f:orderedList)
           result+=(f._name);
        
        if (!checkResult(result, outcomes))
            fail ("No outcome matched "+result);
    }
    
    
    public void testCircular1 ()
    throws Exception
    {
        
        //A: after B
        //B: after A
        
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new RelativeOrdering();
        
        //A: after B
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        ((RelativeOrdering)processor._ordering).addNoOthers(f1);
        f1._afters.add("B");
        
        //B: after A
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        ((RelativeOrdering)processor._ordering).addNoOthers(f2);
        f2._afters.add("A");

        try
        {
            List<Fragment> orderedList = processor._ordering.order();
            fail("No circularity detected");
        }
        catch (Exception e)
        {
            assertTrue (e instanceof IllegalStateException);
        }
    }
 
    
    public void testInvalid1 ()
    throws Exception
    {      
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new RelativeOrdering();
        
        //A: after others, before C
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        f1._hasOther=true;
        ((RelativeOrdering)processor._ordering).addAfterOthers(f1);
        f1._befores.add("C");
        
        //B: before others, after C
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        f2._hasOther = true;
        ((RelativeOrdering)processor._ordering).addBeforeOthers(f2);
        f2._afters.add("C");
        
        //C: no ordering
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        ((RelativeOrdering)processor._ordering).addNoOthers(f3);
        
        try
        {
            List<Fragment> orderedList = processor._ordering.order();
            fail("A and B have an impossible relationship to C");
        }
        catch (Exception e)
        {
            assertTrue (e instanceof IllegalStateException);
        }
    }

    
    public void testAbsoluteOrdering1 ()
    throws Exception
    {
        /*
         * A,B,C,others
         */
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new AbsoluteOrdering();
        ((AbsoluteOrdering)processor._ordering).add("A");
        ((AbsoluteOrdering)processor._ordering).add("B");
        ((AbsoluteOrdering)processor._ordering).add("C");
        ((AbsoluteOrdering)processor._ordering).addOthers();
        
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        
        Fragment f4 = new Fragment((Resource)null, processor);
        f4._name="D"; 
        processor._webFragmentNameMap.put(f4._name, f4);
        
        Fragment f5 = new Fragment((Resource)null, processor);
        f5._name="E";
        processor._webFragmentNameMap.put(f5._name, f5);
        
        Fragment f6 = new Fragment((Resource)null, processor);
        f6._name=Fragment.NAMELESS+"1";
        processor._webFragmentNameMap.put(f6._name, f6);
        
        List<Fragment> list = processor._ordering.order();
        
        String[] outcomes = {"ABCDE"+f6._name};
        String result = "";
        for (Fragment f:list)
            result += f._name;
        
        if (!checkResult(result, outcomes))
            fail("No outcome matched "+result);
    }
    
    
    public void testAbsoluteOrdering2 ()
    throws Exception
    {
        // A,B,C
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new AbsoluteOrdering();
        ((AbsoluteOrdering)processor._ordering).add("A");
        ((AbsoluteOrdering)processor._ordering).add("B");
        ((AbsoluteOrdering)processor._ordering).add("C");
        
        Fragment f1 = new Fragment((Resource)null, processor);
        f1._name = "A";
        processor._webFragmentNameMap.put(f1._name, f1);
        
        Fragment f2 = new Fragment((Resource)null, processor);
        f2._name="B";
        processor._webFragmentNameMap.put(f2._name, f2);
        
        Fragment f3 = new Fragment((Resource)null, processor);
        f3._name="C";
        processor._webFragmentNameMap.put(f3._name, f3);
        
        Fragment f4 = new Fragment((Resource)null, processor);
        f4._name="D"; 
        processor._webFragmentNameMap.put(f4._name, f4);
        
        Fragment f5 = new Fragment((Resource)null, processor);
        f5._name="E";
        processor._webFragmentNameMap.put(f5._name, f5);
        
        Fragment f6 = new Fragment((Resource)null, processor);
        f6._name=Fragment.NAMELESS+"1";
        processor._webFragmentNameMap.put(f6._name, f6);
        
        List<Fragment> list = processor._ordering.order();
        String[] outcomes = {"ABC"};
        String result = "";
        for (Fragment f:list)
            result += f._name;
        
        if (!checkResult(result, outcomes))
            fail("No outcome matched "+result);
    }
    
    public void testAbsoluteOrdering3 ()
    throws Exception
    {
        //empty <absolute-ordering>
        
        WebAppContext wac = new WebAppContext();
        WebXmlProcessor processor = new WebXmlProcessor(wac);
        processor._ordering = processor.new AbsoluteOrdering();
        
        List<Fragment> list = processor._ordering.order();
        assertTrue(list.isEmpty());
    }
    
    public boolean checkResult (String result, String[] outcomes)
    {
        boolean matched = false;
        for (String s:outcomes)
        {
            if (s.equals(result))
                matched = true;
        }
        return matched;
    }
}
