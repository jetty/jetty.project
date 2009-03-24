// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.TestSuite;

import org.eclipse.jetty.util.IO;


/* ------------------------------------------------------------ */
/** Util meta Tests.
 * 
 */
public class IOTest extends junit.framework.TestCase
{
    public IOTest(String name)
    {
      super(name);
    }
    
    public static junit.framework.Test suite() {
        TestSuite suite = new TestSuite(IOTest.class);
        return suite;                  
    }

    /* ------------------------------------------------------------ */
    /** main.
     */
    public static void main(String[] args)
    {
      junit.textui.TestRunner.run(suite());
    }    
    
    

    /* ------------------------------------------------------------ */
    public void testIO() throws InterruptedException
    {
        // Only a little test
        ByteArrayInputStream in = new ByteArrayInputStream
            ("The quick brown fox jumped over the lazy dog".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IO.copyThread(in,out);
        Thread.sleep(1500);
        System.err.println(out);

        assertEquals( "copyThread",
                      out.toString(),
                      "The quick brown fox jumped over the lazy dog");
    }

    

    /* ------------------------------------------------------------ */
    public void testStringSpeed()
    {
        String s="012345678901234567890000000000000000000000000";
        char[] ca = new char[s.length()];
        int loops=1000000;
        
        long start=System.currentTimeMillis();
        long result=0;
        for (int loop=0;loop<loops;loop++)
        {
            for (int c=s.length();c-->0;)
                result+=s.charAt(c);
        }
        long end=System.currentTimeMillis();
        System.err.println("charAt   "+(end-start)+" "+result);
        
        start=System.currentTimeMillis();
        result=0;
        for (int loop=0;loop<loops;loop++)
        {
            s.getChars(0, s.length(), ca, 0);
            for (int c=s.length();c-->0;)
                result+=ca[c];
        }
        end=System.currentTimeMillis();
        System.err.println("getChars "+(end-start)+" "+result);
        
    }
}
