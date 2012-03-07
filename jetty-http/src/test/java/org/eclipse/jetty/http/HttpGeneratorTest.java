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

package org.eclipse.jetty.http;

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.matchers.JUnitMatchers.containsString;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.swing.text.View;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;

/**
 *
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class HttpGeneratorTest
{
    public final static String CONTENT="The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";
    public final static String[] connect={null,"keep-alive","close","TE, close"};

    

    @Test
    public void testResponseNoContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(8096);
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        fields.add("Host","something");
        fields.add("User-Agent","test");

        gen.setRequest(HttpMethod.GET,"/index.html",HttpVersion.HTTP_1_1);
        
        HttpGenerator.Result 
        result=gen.complete(null,null);
        assertEquals(HttpGenerator.State.COMPLETING_UNCOMMITTED,gen.getState());
        assertEquals(HttpGenerator.Result.NEED_COMMIT,result);
        
        result=gen.commit(fields,header,null,null,true);
        assertEquals(HttpGenerator.Result.NEED_COMPLETE,result);
        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,not(containsString("Content-Length")));
  
        result=gen.complete(null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        
        
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals(0,gen.getContentWritten());    
    }
    
    @Test
    public void testResponseWithSmallContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(8096);
        ByteBuffer content=BufferUtil.toBuffer("Hello World");
        ByteBuffer content1=BufferUtil.toBuffer(". The quick brown fox jumped over the lazy dog.");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setResponse(200,null);
        fields.add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result 
        
        result=gen.prepareContent(null,null,content);
        assertEquals(HttpGenerator.Result.NEED_BUFFER,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        
        result=gen.prepareContent(null,buffer,content);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World",BufferUtil.toString(buffer));
        assertTrue(BufferUtil.isEmpty(content));
        
        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World. The quick brown fox jumped over the lazy dog.",BufferUtil.toString(buffer));
        assertTrue(BufferUtil.isEmpty(content));

        result=gen.complete(null,buffer);
        assertEquals(HttpGenerator.Result.NEED_COMMIT,result);
        assertEquals(HttpGenerator.State.COMPLETING_UNCOMMITTED,gen.getState());
        
        result=gen.commit(fields,header,buffer,content,true);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMPLETING,gen.getState());
        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        String body = BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);
        
        result=gen.complete(null,buffer);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        
        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,containsString("Content-Length: 58"));
        assertTrue(head.endsWith("\r\n\r\n"));
        
        assertEquals("Hello World. The quick brown fox jumped over the lazy dog.",body);
        
        assertEquals(58,gen.getContentWritten());    
    }

    @Test
    public void testResponseWithChunkedContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(16);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setResponse(200,null);
        fields.add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result 
        
        result=gen.prepareContent(null,null,content0);
        assertEquals(HttpGenerator.Result.NEED_BUFFER,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        
        result=gen.prepareContent(null,buffer,content0);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World! ",BufferUtil.toString(buffer));
        assertEquals(0,content0.remaining());
        
        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.NEED_COMMIT,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());

        result=gen.commit(fields,header,buffer,content1,false);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());
        assertTrue(gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        String body = BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.NEED_CHUNK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());

        ByteBuffer chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        result=gen.prepareContent(chunk,buffer,content1);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("\r\n10\r\n",BufferUtil.toString(chunk));
        assertEquals(" quick brown fox",BufferUtil.toString(buffer));
        assertEquals(27,content1.remaining());
        body += BufferUtil.toString(chunk)+BufferUtil.toString(buffer);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.prepareContent(chunk,buffer,content1);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("\r\n10\r\n",BufferUtil.toString(chunk));
        assertEquals(" jumped over the",BufferUtil.toString(buffer));
        assertEquals(11,content1.remaining());
        body += BufferUtil.toString(chunk)+BufferUtil.toString(buffer);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.prepareContent(chunk,buffer,content1);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("",BufferUtil.toString(chunk));
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        assertEquals(0,content1.remaining());
        
        result=gen.complete(chunk,buffer);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMPLETING,gen.getState());
        assertEquals("\r\nB\r\n",BufferUtil.toString(chunk));
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        body += BufferUtil.toString(chunk)+BufferUtil.toString(buffer);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.complete(chunk,buffer);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals("\r\n0\r\n\r\n",BufferUtil.toString(chunk));
        assertEquals(0,buffer.remaining());
        body += BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.complete(chunk,buffer);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        
        assertEquals(59,gen.getContentWritten());   
        
        // System.err.println(head+body);
        
        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,not(containsString("Content-Length")));
        assertThat(head,containsString("Transfer-Encoding: chunked"));
        assertTrue(head.endsWith("\r\n\r\n10\r\n"));
    }

    @Test
    public void testResponseWithLargeChunkedContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer content0=BufferUtil.toBuffer("Hello Cruel World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();
        gen.setLargeContent(8);

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setResponse(200,null);
        fields.add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result 
        
        result=gen.prepareContent(null,null,content0);
        assertEquals(HttpGenerator.Result.NEED_COMMIT,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        
        result=gen.commit(fields,header,null,content0,false);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertTrue(gen.isChunking());
        
        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        String body = BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result=gen.commit(fields,header,null,content0,false);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        
        result=gen.prepareContent(null,null,content1);
        assertEquals(HttpGenerator.Result.NEED_CHUNK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());

        ByteBuffer chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        result=gen.prepareContent(chunk,null,content1);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("\r\n2E\r\n",BufferUtil.toString(chunk));
        
        body += BufferUtil.toString(chunk)+BufferUtil.toString(content1);
        BufferUtil.clear(content1);
        
        result=gen.complete(chunk,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals("\r\n0\r\n\r\n",BufferUtil.toString(chunk));
        body += BufferUtil.toString(chunk);
        
        result=gen.complete(chunk,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        
        assertEquals(65,gen.getContentWritten());   
        
        // System.err.println(head+body);

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,not(containsString("Content-Length")));
        assertThat(head,containsString("Transfer-Encoding: chunked"));
        assertTrue(head.endsWith("\r\n\r\n13\r\n"));
    }


    @Test
    public void testResponseWithKnownContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(16);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setResponse(200,null);
        fields.add("Last-Modified",HttpFields.__01Jan1970);
        fields.add("Content-Length","59");
        gen.setContentLength(59);
        
        HttpGenerator.Result 
        
        result=gen.prepareContent(null,null,content0);
        assertEquals(HttpGenerator.Result.NEED_BUFFER,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        
        result=gen.prepareContent(null,buffer,content0);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World! ",BufferUtil.toString(buffer));
        assertEquals(0,content0.remaining());
        
        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.NEED_COMMIT,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());

        result=gen.commit(fields,header,buffer,content1,false);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());
        assertTrue(!gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        String body = BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals(" quick brown fox",BufferUtil.toString(buffer));
        assertEquals(27,content1.remaining());
        body += BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals(" jumped over the",BufferUtil.toString(buffer));
        assertEquals(11,content1.remaining());
        body += BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.prepareContent(null,buffer,content1);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        assertEquals(0,content1.remaining());
        
        result=gen.complete(null,buffer);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMPLETING,gen.getState());
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        body += BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.complete(null,buffer);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals(0,buffer.remaining());
        
        assertEquals(59,gen.getContentWritten());   
        
        // System.err.println(head+body);

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,containsString("Content-Length: 59"));
        assertThat(head,not(containsString("chunked")));
        assertTrue(head.endsWith("\r\n\r\n"));
    }

    @Test
    public void testResponseWithKnownLargeContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();
        gen.setLargeContent(8);

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setResponse(200,null);
        fields.add("Last-Modified",HttpFields.__01Jan1970);
        fields.add("Content-Length","59");
        gen.setContentLength(59);
        
        HttpGenerator.Result 
        
        result=gen.prepareContent(null,null,content0);
        assertEquals(HttpGenerator.Result.NEED_COMMIT,result);
        assertEquals(HttpGenerator.State.START,gen.getState());

        result=gen.commit(fields,header,null,content0,false);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertTrue(!gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        String body = BufferUtil.toString(content0);
        BufferUtil.clear(content0);
        
        result=gen.commit(fields,header,null,null,false);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        
        result=gen.prepareContent(null,null,content1);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        body += BufferUtil.toString(content1);
        BufferUtil.clear(content1);
        
        result=gen.complete(null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());

        result=gen.complete(null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        
        assertEquals(59,gen.getContentWritten());   
        
        // System.err.println(head+body);

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,containsString("Content-Length: 59"));
        assertThat(head,not(containsString("chunked")));
        assertTrue(head.endsWith("\r\n\r\n"));
    }
    
    
    
    
    @Test
    public void testHTTP() throws Exception
    {
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();
        // Handler handler = new Handler();
        // HttpParser parser=null;

        
        // For HTTP version
        for (int v=9;v<=11;v++)
        {
            // For each test result
            for (int r=0;r<tr.length;r++)
            {
                // chunks = 1 to 3
                for (int chunks=1;chunks<=6;chunks++)
                {
                    // For none, keep-alive, close
                    for (int c=0;c<(v==11?connect.length:(connect.length-1));c++)
                    {
                        String t="v="+v+",r="+r+",chunks="+chunks+",connect="+c+",tr="+tr[r];
                        // System.err.println(t);

                        gen.reset();
                        fields.clear();

                        String response=tr[r].build(v,gen,"OK\r\nTest",connect[c],null,chunks, fields);
                        
                        System.out.println("RESPONSE: "+t+"\n"+response+(gen.isPersistent()?"...\n":"---\n"));

                        if (v==9)
                        {
                            assertFalse(t,gen.isPersistent());
                            if (tr[r]._body!=null)
                                assertEquals(t,tr[r]._body, response);
                            continue;
                        }

                        /*
                        parser=new HttpParser(new ByteArrayBuffer(response.getBytes()), handler);
                        parser.setHeadResponse(tr[r]._head);
                                
                        try
                        {
                            parser.parse();
                        }
                        catch(IOException e)
                        {
                            if (tr[r]._body!=null)
                                throw new Exception(t,e);
                            continue;
                        }

                        if (tr[r]._body!=null)
                            assertEquals(t,tr[r]._body, this.content);
                        
                        if (v==10)
                            assertTrue(t,hb.isPersistent() || tr[r]._contentLength==null || c==2 || c==0);
                        else
                            assertTrue(t,hb.isPersistent() ||  c==2 || c==3);

                        if (v>9)
                            assertEquals("OK  Test",f2);

                        if (content==null)
                            assertTrue(t,tr[r]._body==null);
                        else
                            assertTrue(t,tr[r]._contentLength==null || content.length()==Integer.parseInt(tr[r]._contentLength));
                            */
                    }
                }
            }
        }
    }

    private static final String[] headers= { "Content-Type","Content-Length","Connection","Transfer-Encoding","Other"};
    private static class TR
    {
        private int _code;
        private String _body;
        private boolean _head;
        String _contentType;
        String _contentLength;
        String _connection;
        String _te;
        String _other;

        private TR(int code,String contentType, String contentLength ,String content,boolean head)
        {
            _code=code;
            _contentType=contentType;
            _contentLength=contentLength;
            _other="value";
            _body=content;
            _head=head;
        }

        private String build(int version,HttpGenerator gen,String reason, String connection, String te, int chunks, HttpFields fields) throws Exception
        {
            String response="";
            _connection=connection;
            _te=te;
            gen.setVersion(HttpVersion.fromVersion(version));
            gen.setResponse(_code,reason);
            gen.setHead(_head);
           
            if (_contentType!=null)
                fields.put("Content-Type",_contentType);
            if (_contentLength!=null)
            {
                fields.put("Content-Length",_contentLength);
                gen.setContentLength(Long.parseLong(_contentLength));
            }
            if (_connection!=null)
                fields.put("Connection",_connection);
            if (_te!=null)
                fields.put("Transfer-Encoding",_te);
            if (_other!=null)
                fields.put("Other",_other);
            
            ByteBuffer content=_body==null?null:BufferUtil.toBuffer(_body);
            if (content!=null)
                content.limit(0);
            ByteBuffer chunk=null;
            ByteBuffer buffer=null;
            
            
            mainLoop: while(true)
            {
                // if we have unwritten content
                if (content!=null && content.position()<content.capacity())
                {
                    // if we need a new chunk
                    if (content.remaining()==0)
                    {
                        if (chunks-->1)
                            content.limit(content.position()+content.remaining()/2);
                        else
                            content.limit(content.capacity());
                    }
                    

                    switch(gen.getState())
                    {
                        case START:
                        case COMPLETING_UNCOMMITTED:
                        case COMMITTED:
                        case COMPLETING:
                        case END:
                        
                    }
                    
                }
                
                
                    switch(gen.prepareContent(chunk,buffer,content))
                    {
                        case FLUSH:
                            if (BufferUtil.hasContent(chunk))
                            {
                                response+=BufferUtil.toString(chunk);
                                chunk.position(chunk.limit());
                            }
                            if (BufferUtil.hasContent(buffer))
                            {
                                response+=BufferUtil.toString(buffer);
                                buffer.position(buffer.limit());
                            }
                            break;
                            
                        case FLUSH_CONTENT:
                            if (BufferUtil.hasContent(chunk))
                            {
                                response+=BufferUtil.toString(chunk);
                                chunk.position(chunk.limit());
                            }
                            if (BufferUtil.hasContent(content))
                            {
                                response+=BufferUtil.toString(content);
                                content.position(content.limit());
                            }
                            break;
                            
                        case NEED_BUFFER:
                            buffer=BufferUtil.allocate(8192);
                            break;

                        case NEED_CHUNK:
                            chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                            break;

                        case NEED_COMMIT:
                        {
                            commitLoop: while (true)
                            {
                                ByteBuffer header=BufferUtil.allocate(4096);
                                switch(gen.commit(fields,header,buffer,content,chunks==0))
                                {
                                    case FLUSH:
                                        if (BufferUtil.hasContent(header))
                                        {
                                            response+=BufferUtil.toString(header);
                                            header.position(header.limit());
                                        }
                                        if (BufferUtil.hasContent(buffer))
                                        {
                                            response+=BufferUtil.toString(buffer);
                                            buffer.position(buffer.limit());
                                        }
                                        break;

                                    case FLUSH_CONTENT:
                                        if (BufferUtil.hasContent(header))
                                        {
                                            response+=BufferUtil.toString(header);
                                            header.position(header.limit());
                                        }
                                        if (BufferUtil.hasContent(content))
                                        {
                                            response+=BufferUtil.toString(content);
                                            content.position(content.limit());
                                        }
                                        break;

                                    case NEED_BUFFER:
                                        buffer=BufferUtil.allocate(8192);
                                        break;

                                    case OK:
                                        break commitLoop;
                                        
                                    default:
                                        throw new IllegalStateException(gen.toString());
                                }
                            }
                        }
                        break;

                            
                        case NEED_COMPLETE:
                        {
                            completeLoop: while (true)
                            {
                                ByteBuffer header=BufferUtil.allocate(4096);
                                switch(gen.complete(chunk,buffer))
                                {
                                    case FLUSH:
                                        if (BufferUtil.hasContent(chunk))
                                        {
                                            response+=BufferUtil.toString(chunk);
                                            chunk.position(chunk.limit());
                                        }
                                        if (BufferUtil.hasContent(buffer))
                                        {
                                            response+=BufferUtil.toString(buffer);
                                            buffer.position(buffer.limit());
                                        }
                                        break;

                                    case OK:
                                        break completeLoop;
                                        
                                    default:
                                        throw new IllegalStateException(gen.toString());
                                }
                            }
                        }
                        break;
                    }
                
                    continue;
                }
                

                while (true)
                {
                    switch(gen.complete(chunk,buffer))
                    {
                        case FLUSH:
                            if (BufferUtil.hasContent(chunk))
                            {
                                response+=BufferUtil.toString(chunk);
                                chunk.position(chunk.limit());
                            }
                            if (BufferUtil.hasContent(buffer))
                            {
                                response+=BufferUtil.toString(buffer);
                                buffer.position(buffer.limit());
                            }
                            break;

                        case OK:
                            break mainLoop;
                            
                        default:
                            throw new IllegalStateException(gen.toString());
                    }
                }
            }
            
            return response;
        }

        @Override
        public String toString()
        {
            return "["+_code+","+_contentType+","+_contentLength+","+(_body==null?"null":"content")+"]";
        }
    }

    private final TR[] tr =
    {
      /* 0 */  new TR(200,null,null,null,false),
      /* 1 */  new TR(200,null,null,CONTENT,false),
      /* 2 */  new TR(200,null,""+CONTENT.length(),null,true),
      /* 3 */  new TR(200,null,""+CONTENT.length(),CONTENT,false),
      /* 4 */  new TR(200,"text/html",null,null,true),
      /* 5 */  new TR(200,"text/html",null,CONTENT,false),
      /* 6 */  new TR(200,"text/html",""+CONTENT.length(),null,true),
      /* 7 */  new TR(200,"text/html",""+CONTENT.length(),CONTENT,false),
    };

    private String content;
    private String f0;
    private String f1;
    private String f2;
    private String[] hdr;
    private String[] val;
    private int h;

    private class Handler extends HttpParser.EventHandler
    {
        private int index=0;

        @Override
        public void content(ByteBuffer ref)
        {
            if (index == 0)
                content= "";
            content= content.substring(0, index) + ref;
            index+=ref.length();
        }

        @Override
        public void startRequest(ByteBuffer tok0, ByteBuffer tok1, ByteBuffer tok2)
        {
            h= -1;
            hdr= new String[9];
            val= new String[9];
            f0= tok0.toString();
            f1= tok1.toString();
            if (tok2!=null)
                f2= tok2.toString();
            else
                f2=null;
            index=0;
            // System.out.println(f0+" "+f1+" "+f2);
        }

        /* (non-Javadoc)
         * @see org.eclipse.jetty.EventHandler#startResponse(org.eclipse.io.Buffer, int, org.eclipse.io.Buffer)
         */
        @Override
        public void startResponse(ByteBuffer version, int status, ByteBuffer reason)
        {
            h= -1;
            hdr= new String[9];
            val= new String[9];
            f0= version.toString();
            f1= ""+status;
            if (reason!=null)
                f2= reason.toString();
            else
                f2=null;
            index=0;
        }

        @Override
        public void parsedHeader(ByteBuffer name,ByteBuffer value)
        {
            hdr[++h]= name.toString();
            val[h]= value.toString();
        }

        @Override
        public void headerComplete()
        {
            content= null;
        }

        @Override
        public void messageComplete(long contentLength)
        {
        }
    }
}
