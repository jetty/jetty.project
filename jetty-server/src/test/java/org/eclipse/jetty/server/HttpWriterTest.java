package org.eclipse.jetty.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Timer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpWriterTest
{
    private HttpWriter _writer;
    private ByteBuffer _bytes;

    @Before
    public void init() throws Exception
    {
        _bytes = BufferUtil.allocate(2048);

        HttpChannel channel = new HttpChannel(null,null,null)
        {
            @Override
            public HttpConnector getHttpConnector()
            {
                return null;
            }

            @Override
            protected int write(ByteBuffer content) throws IOException
            {
                return BufferUtil.append(content,_bytes);
            }

            @Override
            protected void commit(ResponseInfo info, ByteBuffer content) throws IOException
            {
            }

            @Override
            protected int getContentBufferSize()
            {
                return 0;
            }

            @Override
            protected void increaseContentBufferSize(int size)
            {
            }

            @Override
            protected void resetBuffer()
            {
                BufferUtil.clear(_bytes);
            }

            @Override
            protected void flushResponse() throws IOException
            {
            }

            @Override
            protected void completeResponse() throws IOException
            {
            }

            @Override
            protected void completed()
            {
            }

            @Override
            protected void execute(Runnable task)
            {
                task.run();
            }

            @Override
            public Timer getTimer()
            {
                return null;
            }
            
        };
   
        HttpOutput httpOut = new HttpOutput(channel);
        _writer = new HttpWriter(httpOut);
    }

    @Test
    public void testSimpleUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        _writer.write("Now is the time");
        assertArrayEquals("Now is the time".getBytes(StringUtil.__UTF8),BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        _writer.write("How now \uFF22rown cow");
        assertArrayEquals("How now \uFF22rown cow".getBytes(StringUtil.__UTF8),BufferUtil.toArray(_bytes));
    }
    
    @Test
    public void testNotCESU8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        String data="xxx\uD801\uDC00xxx";
        _writer.write(data);
        assertEquals("787878F0909080787878",TypeUtil.toHexString(BufferUtil.toArray(_bytes)));
        assertArrayEquals(data.getBytes(StringUtil.__UTF8),BufferUtil.toArray(_bytes));
        assertEquals(3+4+3,_bytes.remaining());
        
        Utf8StringBuilder buf = new Utf8StringBuilder();
        buf.append(BufferUtil.toArray(_bytes),0,_bytes.remaining());
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

        assertEquals(sb.toString(),new String(BufferUtil.toArray(_bytes),StringUtil.__UTF8));
    }

    @Test
    public void testISO8859() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__ISO_8859_1);
        _writer.write("How now \uFF22rown cow");
        assertEquals("How now ?rown cow",new String(BufferUtil.toArray(_bytes),StringUtil.__ISO_8859_1));
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
        myReportBytes(BufferUtil.toArray(_bytes));

        assertArrayEquals(bytes,BufferUtil.toArray(_bytes));
        assertArrayEquals(baos.toByteArray(),BufferUtil.toArray(_bytes));
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
        myReportBytes(BufferUtil.toArray(_bytes));

        assertArrayEquals(bytes,BufferUtil.toArray(_bytes));
        assertArrayEquals(baos.toByteArray(),BufferUtil.toArray(_bytes));
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
        myReportBytes(BufferUtil.toArray(_bytes));

        assertArrayEquals(bytes,BufferUtil.toArray(_bytes));
        assertArrayEquals(baos.toByteArray(),BufferUtil.toArray(_bytes));
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
