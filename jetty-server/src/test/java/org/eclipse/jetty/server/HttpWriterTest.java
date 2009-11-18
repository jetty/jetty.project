package org.eclipse.jetty.server;

import java.io.IOException;

import junit.framework.TestCase;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.util.StringUtil;

public class HttpWriterTest extends TestCase
{
    HttpWriter _writer;
    ByteArrayBuffer _bytes;
    
    /* ------------------------------------------------------------ */
    @Override
    protected void setUp() throws Exception
    {
        _bytes = new ByteArrayBuffer(2048);
        
        Buffers buffers = new SimpleBuffers(new ByteArrayBuffer(1024),new ByteArrayBuffer(1024));
        ByteArrayEndPoint endp = new ByteArrayEndPoint();
        AbstractGenerator generator =  new AbstractGenerator(buffers,endp)
        {
            @Override
            public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
            {
            }

            @Override
            public long flushBuffer() throws IOException
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
        
        HttpOutput httpOut = new HttpOutput(generator,60000);
        _writer = new HttpWriter(httpOut);
    }
    
    private void assertArrayEquals(byte[] b1, byte[] b2)
    {
        assertEquals(b1.length,b2.length);
        for (int i=0;i<b1.length;i++)
            assertEquals(""+i,b1[i],b2[i]);
    }
    
    public void testSimpleUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        _writer.write("Now is the time");      
        assertArrayEquals("Now is the time".getBytes(StringUtil.__UTF8),_bytes.asArray());
    }
    
    public void testUTF8() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__UTF8);
        _writer.write("How now \uFF22rown cow");      
        assertArrayEquals("How now \uFF22rown cow".getBytes(StringUtil.__UTF8),_bytes.asArray());
    }
    
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
     
    public void testISO8859() throws Exception
    {
        _writer.setCharacterEncoding(StringUtil.__ISO_8859_1);
        _writer.write("How now \uFF22rown cow");      
        assertEquals("How now ?rown cow",new String(_bytes.asArray(),StringUtil.__ISO_8859_1));
    }

    public void testOutput()
        throws Exception
    {
        Buffer sb=new ByteArrayBuffer(1500);
        Buffer bb=new ByteArrayBuffer(8096);
        HttpFields fields = new HttpFields();
        ByteArrayEndPoint endp = new ByteArrayEndPoint(new byte[0],4096);
        
        HttpGenerator hb = new HttpGenerator(new SimpleBuffers(sb,bb),endp);

        hb.setResponse(200,"OK");
        
        HttpOutput output = new HttpOutput(hb,10000);
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
}
