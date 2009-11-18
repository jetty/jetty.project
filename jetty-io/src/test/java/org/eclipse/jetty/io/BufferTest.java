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

import java.io.File;

import junit.framework.TestCase;

import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.RandomAccessFileBuffer;
import org.eclipse.jetty.util.StringUtil;

/**
 * 
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class BufferTest extends TestCase
{
    Buffer[] buffer;
    
    public static void main(String[] args)
    {
    }

    /*
     * @see TestCase#setUp()
     */
    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        File file = File.createTempFile("test",".buf");
        file.deleteOnExit();
        file.createNewFile();
        
        buffer=new Buffer[]{
          new RandomAccessFileBuffer(file,10),
          new ByteArrayBuffer(10),
          new IndirectNIOBuffer(10),
          new DirectNIOBuffer(10)
        };
    }

    /*
     * @see TestCase#tearDown()
     */
    @Override
    protected void tearDown() throws Exception
    {
        super.tearDown();
    }
    
    /*
     * 
     */
    public void testBuffer()
        throws Exception
    {
        for (int i=0;i<buffer.length;i++)
        {
            String t="t"+i;
            Buffer b = buffer[i];
            
            assertEquals(t,0,b.length());
            assertEquals(t,10,b.capacity());
            assertEquals(t,10,b.space());
            
            b.put((byte)0);
            b.put((byte)1);
            b.put((byte)2);
            assertEquals(t,3,b.length());
            assertEquals(t,10,b.capacity());
            assertEquals(t,7,b.space());
            
            assertEquals(t,0,b.get());
            assertEquals(t,1,b.get());
            assertEquals(t,1,b.length());
            assertEquals(t,10,b.capacity());
            assertEquals(t,7,b.space());
            b.compact();
            assertEquals(t,9,b.space());
            
            byte[] ba = { (byte)-1, (byte)3,(byte)4,(byte)5,(byte)6 };
            
            b.put(ba,1,3);
            assertEquals(t,4,b.length());
            assertEquals(t,6,b.space());
            
            byte[] bg = new byte[4];
            b.get(bg,1,2);
            assertEquals(t,2,bg[1]);
            assertEquals(t,3,bg[2]);
            
            //test getting 0 bytes returns 0
            int count = b.get(bg,0,0);
            assertEquals(t,0, count);
            
            //read up to end
            count = b.get(bg,0,2);
            assertEquals(t, 2, count);
            
            //test reading past end returns -1
            count = b.get(bg,0,1);
            assertEquals(t, -1, count);
        }
    }
    
    public void testHash()
    	throws Exception
    {
        Buffer[] b=
        {
                new ByteArrayBuffer("Test1234 "),
                new ByteArrayBuffer("tEST1234 "),
                new DirectNIOBuffer(4096),
        };
        b[2].put("TeSt1234 ".getBytes(StringUtil.__UTF8));
        
        for (int i=0;i<b.length;i++)
            assertEquals("t"+i,b[0].hashCode(),b[i].hashCode()); 
    }
    
    public void testGet () 
    throws Exception
    {
        Buffer buff = new ByteArrayBuffer(new byte[]{(byte)0,(byte)1,(byte)2,(byte)3,(byte)4,(byte)5});
        
        byte[] readbuff = new byte[2];
        
        int count = buff.get(readbuff, 0, 2);
        assertEquals(2, count);
        assertEquals(readbuff[0], (byte)0);
        assertEquals(readbuff[1], (byte)1);
        
        count = buff.get(readbuff, 0, 2);
        assertEquals(2, count);
        assertEquals(readbuff[0], (byte)2);
        assertEquals(readbuff[1], (byte)3);
        
        count = buff.get(readbuff, 0, 0);
        assertEquals(0, count);
        
        readbuff[0]=(byte)9;
        readbuff[1]=(byte)9;
        
        count = buff.get(readbuff, 0, 2);
        assertEquals(2, count);
        
        count = buff.get(readbuff, 0, 2);
        assertEquals(-1, count);
        
    }
    
    public void testInsensitive()
    {
        Buffer cs0 = new ByteArrayBuffer("Test 1234");
        Buffer cs1 = new ByteArrayBuffer("Test 1234");
        Buffer cs2 = new ByteArrayBuffer("tEst 1234");
        Buffer cs3 = new ByteArrayBuffer("Other    ");
        Buffer ci0 = new ByteArrayBuffer.CaseInsensitive("Test 1234");
        Buffer ci1 = new ByteArrayBuffer.CaseInsensitive("Test 1234");
        Buffer ci2 = new ByteArrayBuffer.CaseInsensitive("tEst 1234");
        Buffer ci3 = new ByteArrayBuffer.CaseInsensitive("oTher    ");

        assertTrue( cs0.equals(cs0));
        assertTrue( cs0.equals(cs1));
        assertTrue(!cs0.equals(cs2));
        assertTrue(!cs0.equals(cs3));
        assertTrue( cs0.equals(ci0));
        assertTrue( cs0.equals(ci1));
        assertTrue( cs0.equals(ci2));
        assertTrue(!cs0.equals(ci3));
        
        assertTrue( cs1.equals(cs0));
        assertTrue( cs1.equals(cs1));
        assertTrue(!cs1.equals(cs2));
        assertTrue(!cs1.equals(cs3));
        assertTrue( cs1.equals(ci0));
        assertTrue( cs1.equals(ci1));
        assertTrue( cs1.equals(ci2));
        assertTrue(!cs1.equals(ci3));
        
        assertTrue(!cs2.equals(cs0));
        assertTrue(!cs2.equals(cs1));
        assertTrue( cs2.equals(cs2));
        assertTrue(!cs2.equals(cs3));
        assertTrue( cs2.equals(ci0));
        assertTrue( cs2.equals(ci1));
        assertTrue( cs2.equals(ci2));
        assertTrue(!cs2.equals(ci3));

        assertTrue(!cs3.equals(cs0));
        assertTrue(!cs3.equals(cs1));
        assertTrue(!cs3.equals(cs2));
        assertTrue( cs3.equals(cs3));
        assertTrue(!cs3.equals(ci0));
        assertTrue(!cs3.equals(ci1));
        assertTrue(!cs3.equals(ci2));
        assertTrue( cs3.equals(ci3));
        
        
        assertTrue( ci0.equals(cs0));
        assertTrue( ci0.equals(cs1));
        assertTrue( ci0.equals(cs2));
        assertTrue(!ci0.equals(cs3));
        assertTrue( ci0.equals(ci0));
        assertTrue( ci0.equals(ci1));
        assertTrue( ci0.equals(ci2));
        assertTrue(!ci0.equals(ci3));
        
        assertTrue( ci1.equals(cs0));
        assertTrue( ci1.equals(cs1));
        assertTrue( ci1.equals(cs2));
        assertTrue(!ci1.equals(cs3));
        assertTrue( ci1.equals(ci0));
        assertTrue( ci1.equals(ci1));
        assertTrue( ci1.equals(ci2));
        assertTrue(!ci1.equals(ci3));
        
        assertTrue( ci2.equals(cs0));
        assertTrue( ci2.equals(cs1));
        assertTrue( ci2.equals(cs2));
        assertTrue(!ci2.equals(cs3));
        assertTrue( ci2.equals(ci0));
        assertTrue( ci2.equals(ci1));
        assertTrue( ci2.equals(ci2));
        assertTrue(!ci2.equals(ci3));

        assertTrue(!ci3.equals(cs0));
        assertTrue(!ci3.equals(cs1));
        assertTrue(!ci3.equals(cs2));
        assertTrue( ci3.equals(cs3));
        assertTrue(!ci3.equals(ci0));
        assertTrue(!ci3.equals(ci1));
        assertTrue(!ci3.equals(ci2));
        assertTrue( ci3.equals(ci3));

    }

    public void testView()
    {
        Buffer b = new ByteArrayBuffer(" Test 1234 ".getBytes());
        b.setGetIndex(b.getIndex()+1);
        b.setPutIndex(b.putIndex()-1);
        View v0 = new View(b);
        View v1 = new View(b);
        View v2 = new View(v0);
        
        String s=b.toString();
        String s0=v0.toString();
        String s1=v1.toString();
        String s2=v2.toString();
        String s3=v0.toString();
        String s4=v1.toString();
        String s5=v2.toString();
        
        assertEquals(s, s0);
        assertEquals(s0, s1);
        assertEquals(s1, s2);
        assertEquals(s2, s3);
        assertEquals(s3, s4);
        assertEquals(s4, s5);
        
    }
    

}
