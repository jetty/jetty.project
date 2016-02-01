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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
/** Util meta Tests.
 *
 */
public class URLEncodedTest
{

    /* -------------------------------------------------------------- */
    static
    {
        /*
         * Uncomment to set setting the System property to something other than the default of UTF-8.
         * Beware however that you will have to @Ignore all the other tests other than testUrlEncodedStream!

            System.setProperty("org.eclipse.jetty.util.UrlEncoding.charset", StringUtil.__ISO_8859_1);
         */
    }


    /* -------------------------------------------------------------- */
    @Test
    public void testUrlEncoded()
    {

        UrlEncoded url_encoded = new UrlEncoded();
        assertEquals("Initially not empty",0, url_encoded.size());

        url_encoded.clear();
        url_encoded.decode("");
        assertEquals("Not empty after decode(\"\")",0, url_encoded.size());

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
        url_encoded.decode("Name11=%u30EDxxVerdi+%C6+og+2zz", StandardCharsets.ISO_8859_1);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", "?xxVerdi \u00c6 og 2zz",url_encoded.getString("Name11"));

        url_encoded.clear();
        url_encoded.decode("Name12=%u30EDxxVerdi+%2F+og+2zz", StandardCharsets.UTF_8);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", url_encoded.getString("Name12"),"\u30edxxVerdi / og 2zz");

        url_encoded.clear();
        url_encoded.decode("Name14=%uXXXXa%GGb%+%c%+%d", StandardCharsets.ISO_8859_1);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get","?a?b?c?d", url_encoded.getString("Name14"));

        url_encoded.clear();
        url_encoded.decode("Name14=%uXXXX%GG%+%%+%", StandardCharsets.UTF_8);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", "\ufffd\ufffd\ufffd\ufffd",url_encoded.getString("Name14"));

        
        /* Not every jvm supports this encoding */

        if (java.nio.charset.Charset.isSupported("SJIS"))
        {
            url_encoded.clear();
            url_encoded.decode("Name9=%u30ED%83e%83X%83g", Charset.forName("SJIS")); // "Test" in Japanese Katakana
            assertEquals("encoded param size",1, url_encoded.size());
            assertEquals("encoded get", "\u30ed\u30c6\u30b9\u30c8", url_encoded.getString("Name9"));   
        }
        else
            assertTrue("Charset SJIS not supported by jvm", true);
    }


    /* -------------------------------------------------------------- */
    @Test
    public void testBadEncoding()
    {
        UrlEncoded url_encoded = new UrlEncoded();
        url_encoded.decode("Name15=xx%zzyy", StandardCharsets.UTF_8);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", "xx\ufffdyy", url_encoded.getString("Name15"));

        String bad="Name=%FF%FF%FF";
        MultiMap<String> map = new MultiMap<String>();
        UrlEncoded.decodeUtf8To(bad,map);
        assertEquals("encoded param size",1, map.size());
        assertEquals("encoded get", "\ufffd\ufffd\ufffd", map.getString("Name"));
        
        url_encoded.clear();
        url_encoded.decode("Name=%FF%FF%FF", StandardCharsets.UTF_8);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", "\ufffd\ufffd\ufffd", url_encoded.getString("Name"));
        
        url_encoded.clear();
        url_encoded.decode("Name=%EF%EF%EF", StandardCharsets.UTF_8);
        assertEquals("encoded param size",1, url_encoded.size());
        assertEquals("encoded get", "\ufffd\ufffd", url_encoded.getString("Name"));

        assertEquals("x",UrlEncoded.decodeString("x",0,1,StandardCharsets.UTF_8));
        assertEquals("x\ufffd",UrlEncoded.decodeString("x%",0,2,StandardCharsets.UTF_8));
        assertEquals("x\ufffd",UrlEncoded.decodeString("x%2",0,3,StandardCharsets.UTF_8));
        assertEquals("x ",UrlEncoded.decodeString("x%20",0,4,StandardCharsets.UTF_8));

        assertEquals("xxx",UrlEncoded.decodeString("xxx",0,3,StandardCharsets.UTF_8));
        assertEquals("xxx\ufffd",UrlEncoded.decodeString("xxx%",0,4,StandardCharsets.UTF_8));
        assertEquals("xxx\ufffd",UrlEncoded.decodeString("xxx%u",0,5,StandardCharsets.UTF_8));
        assertEquals("xxx\ufffd",UrlEncoded.decodeString("xxx%u1",0,6,StandardCharsets.UTF_8));
        assertEquals("xxx\ufffd",UrlEncoded.decodeString("xxx%u12",0,7,StandardCharsets.UTF_8));
        assertEquals("xxx\ufffd",UrlEncoded.decodeString("xxx%u123",0,8,StandardCharsets.UTF_8));
        assertEquals("xxx\u1234",UrlEncoded.decodeString("xxx%u1234",0,9,StandardCharsets.UTF_8));
    }


    /* -------------------------------------------------------------- */
    @Test
    public void testUrlEncodedStream()
        throws Exception
    {
        String [][] charsets = new String[][]
        {
           {StringUtil.__UTF8,null,"%30"},
           {StringUtil.__ISO_8859_1,StringUtil.__ISO_8859_1,"%30"},
           {StringUtil.__UTF8,StringUtil.__UTF8,"%30"},
           {StringUtil.__UTF16,StringUtil.__UTF16,"%00%30"},
        };


        for (int i=0;i<charsets.length;i++)
        {
            ByteArrayInputStream in = new ByteArrayInputStream(("name\n=value+"+charsets[i][2]+"&name1=&name2&n\u00e3me3=value+3").getBytes(charsets[i][0]));
            MultiMap<String> m = new MultiMap<>();
            UrlEncoded.decodeTo(in, m, charsets[i][1]==null?null:Charset.forName(charsets[i][1]),-1,-1);
            assertEquals(charsets[i][1]+" stream length",4,m.size());
            assertEquals(charsets[i][1]+" stream name\\n","value 0",m.getString("name\n"));
            assertEquals(charsets[i][1]+" stream name1","",m.getString("name1"));
            assertEquals(charsets[i][1]+" stream name2","",m.getString("name2"));
            assertEquals(charsets[i][1]+" stream n\u00e3me3","value 3",m.getString("n\u00e3me3"));
        }


        if (java.nio.charset.Charset.isSupported("Shift_JIS"))
        {
            ByteArrayInputStream in2 = new ByteArrayInputStream("name=%83e%83X%83g".getBytes(StandardCharsets.ISO_8859_1));
            MultiMap<String> m2 = new MultiMap<>();
            UrlEncoded.decodeTo(in2, m2, Charset.forName("Shift_JIS"),-1,-1);
            assertEquals("stream length",1,m2.size());
            assertEquals("stream name","\u30c6\u30b9\u30c8",m2.getString("name"));
        }
        else
            assertTrue("Charset Shift_JIS not supported by jvm", true);

    }


    /* -------------------------------------------------------------- */
    @Test
    public void testCharsetViaSystemProperty ()
    throws Exception
    {
        /*
         * Uncomment to test setting a non-UTF-8 default character encoding using the SystemProperty org.eclipse.jetty.util.UrlEncoding.charset.
         * You will also need to uncomment the static initializer that sets this SystemProperty near the top of this file.


        ByteArrayInputStream in3 = new ByteArrayInputStream("name=libell%E9".getBytes(StringUtil.__ISO_8859_1));
        MultiMap m3 = new MultiMap();
        UrlEncoded.decodeTo(in3, m3, null, -1);
        assertEquals("stream name", "libell\u00E9", m3.getString("name"));

        */
    }

    /* -------------------------------------------------------------- */
    @Test
    public void testUtf8()
        throws Exception
    {
        UrlEncoded url_encoded = new UrlEncoded();
        assertEquals("Empty",0, url_encoded.size());

        url_encoded.clear();
        url_encoded.decode("text=%E0%B8%9F%E0%B8%AB%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%81%E0%B8%9F%E0%B8%A7%E0%B8%AB%E0%B8%AA%E0%B8%94%E0%B8%B2%E0%B9%88%E0%B8%AB%E0%B8%9F%E0%B8%81%E0%B8%A7%E0%B8%94%E0%B8%AA%E0%B8%B2%E0%B8%9F%E0%B8%81%E0%B8%AB%E0%B8%A3%E0%B8%94%E0%B9%89%E0%B8%9F%E0%B8%AB%E0%B8%99%E0%B8%81%E0%B8%A3%E0%B8%94%E0%B8%B5&Action=Submit");

        String hex ="E0B89FE0B8ABE0B881E0B8A7E0B894E0B8B2E0B988E0B881E0B89FE0B8A7E0B8ABE0B8AAE0B894E0B8B2E0B988E0B8ABE0B89FE0B881E0B8A7E0B894E0B8AAE0B8B2E0B89FE0B881E0B8ABE0B8A3E0B894E0B989E0B89FE0B8ABE0B899E0B881E0B8A3E0B894E0B8B5";
        String expected = new String(TypeUtil.fromHexString(hex),"utf-8");
        Assert.assertEquals(expected,url_encoded.getString("text"));
    }

    /* -------------------------------------------------------------- */
    @Test
    public void testNotUtf8() throws Exception
    {
        String query="name=X%c0%afZ";

        MultiMap<String> map = new MultiMap<>();
        UrlEncoded.LOG.info("EXPECT 4 Not Valid UTF8 warnings...");
        UrlEncoded.decodeUtf8To(query,0,query.length(),map);
        assertEquals("X"+Utf8Appendable.REPLACEMENT+Utf8Appendable.REPLACEMENT+"Z",map.getValue("name",0));

        map.clear();

        UrlEncoded.decodeUtf8To(new ByteArrayInputStream(query.getBytes(StandardCharsets.ISO_8859_1)),map,100,-1);
        assertEquals("X"+Utf8Appendable.REPLACEMENT+Utf8Appendable.REPLACEMENT+"Z",map.getValue("name",0));
    }
}
