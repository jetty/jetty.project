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

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;

import junit.framework.TestSuite;


/* ------------------------------------------------------------ */
/** Util meta Tests.
 * 
 */
public class URLEncodedTest extends junit.framework.TestCase
{
    public URLEncodedTest(String name)
    {
      super(name);
    }
    
    public static junit.framework.Test suite() {
        TestSuite suite = new TestSuite(URLEncodedTest.class);
        return suite;                  
    }

    /* ------------------------------------------------------------ */
    /** main.
     */
    public static void main(String[] args)
    {
      junit.textui.TestRunner.run(suite());
    }    
    

    /* -------------------------------------------------------------- */
    public void testUrlEncoded() throws UnsupportedEncodingException
    {
          
        UrlEncoded url_encoded = new UrlEncoded();
        assertEquals("Empty",0, url_encoded.size());

        url_encoded.clear();
        url_encoded.decode("Name1=Value1");
        assertEquals("simple param size",1, url_encoded.size());
        assertEquals("simple encode","Name1=Value1", url_encoded.encode());
        assertEquals("simple get","Value1", url_encoded.getString("Name1"));
        
        url_encoded.clear();
        url_encoded.decode("Name2=");
        assertEquals("dangling param size",1, url_encoded.size());
        assertEquals("dangling encode","Name2", url_encoded.encode());
        assertEquals("dangling get","", url_encoded.getString("Name2"));
    
        url_encoded.clear();
        url_encoded.decode("Name3");
        assertEquals("noValue param size",1, url_encoded.size());
        assertEquals("noValue encode","Name3", url_encoded.encode());
        assertEquals("noValue get","", url_encoded.getString("Name3"));
    
        url_encoded.clear();
        url_encoded.decode("Name4=V\u0629lue+4%21");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded encode","Name4=V%D8%A9lue+4%21", url_encoded.encode());
        assertEquals("encoded get","V\u0629lue 4!", url_encoded.getString("Name4"));

        url_encoded.clear();
        url_encoded.decode("Name4=Value%2B4%21");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded encode","Name4=Value%2B4%21", url_encoded.encode());
        assertEquals("encoded get","Value+4!", url_encoded.getString("Name4"));
        
        url_encoded.clear();
        url_encoded.decode("Name4=Value+4%21%20%214");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded encode","Name4=Value+4%21+%214", url_encoded.encode());
        assertEquals("encoded get","Value 4! !4", url_encoded.getString("Name4"));

        
        url_encoded.clear();
        url_encoded.decode("Name5=aaa&Name6=bbb");
        assertEquals("multi param size",2, url_encoded.size());
        assertTrue("multi encode "+url_encoded.encode(),
                   url_encoded.encode().equals("Name5=aaa&Name6=bbb") ||
                   url_encoded.encode().equals("Name6=bbb&Name5=aaa")
                   );
        assertEquals("multi get","aaa", url_encoded.getString("Name5"));
        assertEquals("multi get","bbb", url_encoded.getString("Name6"));
    
        url_encoded.clear();
        url_encoded.decode("Name7=aaa&Name7=b%2Cb&Name7=ccc");
        assertEquals("multi encode","Name7=aaa&Name7=b%2Cb&Name7=ccc",url_encoded.encode());
        assertEquals("list get all", url_encoded.getString("Name7"),"aaa,b,b,ccc");
        assertEquals("list get","aaa", url_encoded.getValues("Name7").get(0));
        assertEquals("list get", url_encoded.getValues("Name7").get(1),"b,b");
        assertEquals("list get","ccc", url_encoded.getValues("Name7").get(2));

        url_encoded.clear();
        url_encoded.decode("Name8=xx%2C++yy++%2Czz");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded encode","Name8=xx%2C++yy++%2Czz", url_encoded.encode());
        assertEquals("encoded get", url_encoded.getString("Name8"),"xx,  yy  ,zz");
        
        url_encoded.clear();
        url_encoded.decode("Name11=xxVerdi+%C6+og+2zz", "ISO-8859-1");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", url_encoded.getString("Name11"),"xxVerdi \u00c6 og 2zz");
        
        url_encoded.clear();
        url_encoded.decode("Name12=xxVerdi+%2F+og+2zz", "UTF-8");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", url_encoded.getString("Name12"),"xxVerdi / og 2zz");
        
        url_encoded.clear();
        url_encoded.decode("Name14=%GG%+%%+%", "ISO-8859-1");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", url_encoded.getString("Name14"),"%GG% %% %");
        
        url_encoded.clear();
        url_encoded.decode("Name14=%GG%+%%+%", "UTF-8");
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", url_encoded.getString("Name14"),"%GG% %% %");

        /* Not every jvm supports this encoding */
        
        if (java.nio.charset.Charset.isSupported("SJIS"))
        {
            url_encoded.clear();
            url_encoded.decode("Name9=%83e%83X%83g", "SJIS"); // "Test" in Japanese Katakana
            assertEquals("encoded param size",1, url_encoded.size());
            assertEquals("encoded get", "\u30c6\u30b9\u30c8", url_encoded.getString("Name9"));   
        }
        else
            assertTrue("Charset SJIS not supported by jvm", true);
    }
    

    /* -------------------------------------------------------------- */
    public void testUrlEncodedStream()
    	throws Exception
    {
        String [][] charsets = new String[][]
        {
           {StringUtil.__UTF8,null},
           {StringUtil.__ISO_8859_1,StringUtil.__ISO_8859_1},
           {StringUtil.__UTF8,StringUtil.__UTF8},
           {StringUtil.__UTF16,StringUtil.__UTF16},
        };
        
        for (int i=0;i<charsets.length;i++)
        {
            ByteArrayInputStream in = new ByteArrayInputStream("name\n=value+%30&name1=&name2&n\u00e3me3=value+3".getBytes(charsets[i][0]));
            MultiMap m = new MultiMap();
            UrlEncoded.decodeTo(in, m, charsets[i][1], -1);
            System.err.println(m);
            assertEquals(i+" stream length",4,m.size());
            assertEquals(i+" stream name\\n","value 0",m.getString("name\n"));
            assertEquals(i+" stream name1","",m.getString("name1"));
            assertEquals(i+" stream name2","",m.getString("name2"));
            assertEquals(i+" stream n\u00e3me3","value 3",m.getString("n\u00e3me3"));
        }
        
        
        if (java.nio.charset.Charset.isSupported("Shift_JIS"))
        {
            ByteArrayInputStream in2 = new ByteArrayInputStream ("name=%83e%83X%83g".getBytes());
            MultiMap m2 = new MultiMap();
            UrlEncoded.decodeTo(in2, m2, "Shift_JIS", -1);
            assertEquals("stream length",1,m2.size());
            assertEquals("stream name","\u30c6\u30b9\u30c8",m2.getString("name"));
        }
        else
            assertTrue("Charset Shift_JIS not supported by jvm", true);
    }
    
}
