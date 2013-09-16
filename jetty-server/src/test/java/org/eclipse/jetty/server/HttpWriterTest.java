//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.Before;
import org.junit.Test;

public class HttpWriterTest
{
    private HttpWriter _writer;
    private ByteArrayBuffer _bytes;

    @Before
    public void init() throws Exception
    {
        _bytes = new ByteArrayBuffer(2048);

        Buffers buffers = new SimpleBuffers(new ByteArrayBuffer(1024),new ByteArrayBuffer(1024));
        ByteArrayEndPoint endp = new ByteArrayEndPoint();
        AbstractGenerator generator =  new AbstractGenerator(buffers,endp)
        {
            @Override
            public boolean isRequest()
            {
                return false;
            }

            @Override
            public boolean isResponse()
            {
                return true;
            }

            @Override
            public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
            {
            }

            @Override
            public int flushBuffer() throws IOException
            {
                return 0;
            }

            @Override
            public int prepareUncheckedAddContent() throws IOException
            {
                return 1024;
            }

            public void addContent(Buffer content, boolean last) throws IOException
            {
                _bytes.put(content);
                content.clear();
            }

            public boolean addContent(byte b) throws IOException
            {
                return false;
            }

        };

        AbstractHttpConnection connection = new AbstractHttpConnection(null,endp,new Server(),null,generator,null)
        {
            @Override
            public Connection handle() throws IOException
            {
                return null;
            }
        };
        endp.setMaxIdleTime(60000);
   
        HttpOutput httpOut = new HttpOutput(connection);
        _writer = new HttpWriter(httpOut);
    }

    @Test
    public void testSimpleUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        _writer.write("Now is the time");
        assertArrayEquals("Now is the time".getBytes(StringUtil.__UTF8),_bytes.asArray());
    }

    @Test
    public void testUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        _writer.write("How now \uFF22rown cow");
        assertArrayEquals("How now \uFF22rown cow".getBytes(StringUtil.__UTF8),_bytes.asArray());
    }
    
    @Test
    public void testNotCESU8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        String data="xxx\uD801\uDC00xxx";
        _writer.write(data);
        assertEquals("787878F0909080787878",TypeUtil.toHexString(_bytes.asArray()));
        assertArrayEquals(data.getBytes(StringUtil.__UTF8),_bytes.asArray());
        assertEquals(3+4+3,_bytes.length());
        
        Utf8StringBuilder buf = new Utf8StringBuilder();
        buf.append(_bytes.asArray(),0,_bytes.length());
        assertEquals(data,buf.toString());
        
    }

    @Test
    public void testMultiByteOverflowUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        final String singleByteStr = "a";
        final String multiByteDuplicateStr = "\uFF22";
        int remainSize = 1;

        int multiByteStrByteLength = multiByteDuplicateStr.getBytes("UTF-8").length;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HttpWriter.MAX_OUTPUT_CHARS - multiByteStrByteLength; i++) {
          sb.append(singleByteStr);
        }
        sb.append(multiByteDuplicateStr);
        for (int i = 0; i < remainSize; i++) {
          sb.append(singleByteStr);
        }
        char[] buf = new char[HttpWriter.MAX_OUTPUT_CHARS * 3];

        int length = HttpWriter.MAX_OUTPUT_CHARS - multiByteStrByteLength + remainSize + 1;
        sb.toString().getChars(0, length, buf, 0);

        _writer.write(buf, 0, length);

        assertEquals(sb.toString(),new String(_bytes.asArray(),StringUtil.__UTF8));
    }

    @Test
    public void testISO8859() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__ISO_8859_1);
        _writer.write("How now \uFF22rown cow");
        assertEquals("How now ?rown cow",new String(_bytes.asArray(),StringUtil.__ISO_8859_1));
    }

    @Test
    public void testOutput() throws Exception
    {
        Buffer sb=new ByteArrayBuffer(1500);
        Buffer bb=new ByteArrayBuffer(8096);
        HttpFields fields = new HttpFields();
        ByteArrayEndPoint endp = new ByteArrayEndPoint(new byte[0],4096);

        HttpGenerator hb = new HttpGenerator(new SimpleBuffers(sb,bb),endp);

        hb.setResponse(200,"OK");

        AbstractHttpConnection connection = new AbstractHttpConnection(null,endp,new Server(),null,hb,null)
        {
            @Override
            public Connection handle() throws IOException
            {
                return null;
            }
        };
        endp.setMaxIdleTime(10000);
        hb.setSendServerVersion(false);
        HttpOutput output = new HttpOutput(connection);
        HttpWriter writer = new HttpWriter(output);
        writer.setCharacterEncoding(StringUtil.__UTF8);

        char[] chars = new char[1024];
        for (int i=0;i<chars.length;i++)
            chars[i]=(char)('0'+(i%10));
        chars[0]='\u0553';
        writer.write(chars);

        hb.completeHeader(fields,true);
        hb.flush(10000);
        String response = new String(endp.getOut().asArray(),StringUtil.__UTF8);
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\nContent-Length: 1025\r\n\r\n\u05531234567890"));
    }

    @Test
    public void testUTF16x2() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);

        String source = "\uD842\uDF9F";

        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        _writer.write(source.toCharArray(),0,source.toCharArray().length);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(baos ,StringUtil.__UTF8 );
        osw.write(source.toCharArray(),0,source.toCharArray().length);
        osw.flush();

        myReportBytes(bytes);
        myReportBytes(baos.toByteArray());
        myReportBytes(_bytes.asArray());

        assertArrayEquals(bytes,_bytes.asArray());
        assertArrayEquals(baos.toByteArray(),_bytes.asArray());
    }

    @Test
    public void testMultiByteOverflowUTF16x2() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "\uD842\uDF9F"; 
        int adjustSize = -1;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HttpWriter.MAX_OUTPUT_CHARS + adjustSize; i++)
        {
            sb.append(singleByteStr);
        }
        sb.append(multiByteDuplicateStr);
        for (int i = 0; i < remainSize; i++)
        {
            sb.append(singleByteStr);
        }
        String source = sb.toString();

        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        _writer.write(source.toCharArray(),0,source.toCharArray().length);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(baos ,StringUtil.__UTF8);
        osw.write(source.toCharArray(),0,source.toCharArray().length);
        osw.flush();

        myReportBytes(bytes);
        myReportBytes(baos.toByteArray());
        myReportBytes(_bytes.asArray());

        assertArrayEquals(bytes,_bytes.asArray());
        assertArrayEquals(baos.toByteArray(),_bytes.asArray());
    }
    
    @Test
    public void testMultiByteOverflowUTF16x2_2() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "\uD842\uDF9F"; 
        int adjustSize = -2;   

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HttpWriter.MAX_OUTPUT_CHARS + adjustSize; i++)
        {
            sb.append(singleByteStr);
        }
        sb.append(multiByteDuplicateStr);
        for (int i = 0; i < remainSize; i++)
        {
            sb.append(singleByteStr);
        }
        String source = sb.toString();

        byte[] bytes = source.getBytes(StringUtil.__UTF8);
        _writer.write(source.toCharArray(),0,source.toCharArray().length);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(baos,StringUtil.__UTF8);
        osw.write(source.toCharArray(),0,source.toCharArray().length);
        osw.flush();

        myReportBytes(bytes);
        myReportBytes(baos.toByteArray());
        myReportBytes(_bytes.asArray());

        assertArrayEquals(bytes,_bytes.asArray());
        assertArrayEquals(baos.toByteArray(),_bytes.asArray());
    }

    private void myReportBytes(byte[] bytes) throws Exception
    {
        for (int i = 0; i < bytes.length; i++)
        {
            // System.err.format("%s%x",(i == 0)?"[":(i % (HttpWriter.MAX_OUTPUT_CHARS) == 0)?"][":",",bytes[i]);
        }
        // System.err.format("]->%s\n",new String(bytes,StringUtil.__UTF8));
    }


    private void assertArrayEquals(byte[] b1, byte[] b2)
    {
        String test=new String(b1)+"=="+new String(b2);
        assertEquals(test,b1.length,b2.length);
        for (int i=0;i<b1.length;i++)
            assertEquals(test,b1[i],b2[i]);
    }
}
