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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class Utf8StringBuilderTest
{
    @Test
    public void testInvalid()
        throws Exception
    {
        String[] invalids = {
                "c0af",
                "EDA080",
                "f08080af",
                "f8808080af",
                "e080af",
                "F4908080",
                "fbbfbfbfbf"
        };
        
        for (String i : invalids)
        {
            byte[] bytes = TypeUtil.fromHexString(i);

            /* Test what JVM does
            try
            {
                String s = new String(bytes,0,bytes.length,"UTF-8");
                System.err.println(i+": "+s);
            }
            catch(Exception e)
            {
                System.err.println(i+": "+e);
            }
            */
            
            try
            {
                Utf8StringBuilder buffer = new Utf8StringBuilder();
                buffer.append(bytes,0,bytes.length);
                
                assertEquals(i,"not expected",buffer.toString());
            }
            catch(IllegalArgumentException e)
            {
                assertTrue(i,true);
            }
        }
    }
    
    @Test
    public void testUtfStringBuilder()
        throws Exception
    {
        String source="abcd012345\n\r\u0000\u00a4\u10fb\ufffdjetty";
        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        for (int i=0;i<bytes.length;i++)
            buffer.append(bytes[i]);
        assertEquals(source, buffer.toString());
        assertTrue(buffer.toString().endsWith("jetty")); 
    }
    
    
    
    @Test
    public void testShort()
    throws Exception
    {
        String source="abc\u10fb";
        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        for (int i=0;i<bytes.length-1;i++)
            buffer.append(bytes[i]);
        try
        {
            buffer.toString();
            assertTrue(false);
        }
        catch(Utf8Appendable.NotUtf8Exception e)
        {
            assertTrue(e.toString().indexOf("!UTF-8")>=0);
        }
    }
    
    @Test
    public void testLong()
    throws Exception
    {
        String source="abcXX";
        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        bytes[3]=(byte)0xc0;
        bytes[4]=(byte)0x00;

        Utf8StringBuilder buffer = new Utf8StringBuilder();
        try
        {
            for (int i = 0; i < bytes.length; i++)
                buffer.append(bytes[i]);
            assertTrue(false);
        }
        catch(Utf8Appendable.NotUtf8Exception e)
        {
            assertTrue(true);
        }
        assertEquals("abc\ufffd", buffer.toString());
    }

    
    @Test 
    public void testUTF32codes()
    throws Exception
    {
        String source="\uD842\uDF9F";
        byte[] bytes=source.getBytes("UTF-8");
        
        // System.err.println(TypeUtil.toHexString(bytes));
        String jvmcheck = new String(bytes,0,bytes.length,"UTF-8");
        assertEquals(source,jvmcheck);
        
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        buffer.append(bytes,0,bytes.length);
        String result=buffer.toString();
        assertEquals(source,result);
    }
    

}
