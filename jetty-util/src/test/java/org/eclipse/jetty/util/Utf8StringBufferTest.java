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

package org.eclipse.jetty.util;


public class Utf8StringBufferTest extends junit.framework.TestCase
{

    public void testUtfStringBuffer()
        throws Exception
    {
        String source="abcd012345\n\r\u0000\u00a4\u10fb\ufffdjetty";
        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        Utf8StringBuffer buffer = new Utf8StringBuffer();
        for (int i=0;i<bytes.length;i++)
            buffer.append(bytes[i]);
        assertEquals(source, buffer.toString());
        assertTrue(buffer.toString().endsWith("jetty")); 
    }


    public void testShort()
    throws Exception
    {
        String source="abc\u10fb";
        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        Utf8StringBuffer buffer = new Utf8StringBuffer();
        for (int i=0;i<bytes.length-1;i++)
            buffer.append(bytes[i]);
        try
        {
            buffer.toString();
            assertTrue(false);
        }
        catch(IllegalStateException e)
        {
            assertTrue(e.toString().indexOf("!utf8")>=0);
        }
    }
    
    public void testLong()
    throws Exception
    {
        String source="abcXX";
        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        bytes[3]=(byte)0xc0;
        bytes[4]=(byte)0x00;
        
        Utf8StringBuffer buffer = new Utf8StringBuffer();
        try
        {
            for (int i=0;i<bytes.length;i++)
                buffer.append(bytes[i]);
            buffer.toString();
            assertTrue(false);
        }
        catch(Exception e)
        {
            assertTrue(e.toString().indexOf("!utf8")>=0);
        }
    }
    
    
}
