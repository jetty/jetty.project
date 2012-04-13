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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.matchers.JUnitMatchers.containsString;
import static org.junit.matchers.JUnitMatchers.either;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpGenerator.Action;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;

/**
 *
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class HttpGeneratorServerTest
{
    private class Handler implements HttpParser.ResponseHandler
    {
        @Override
        public boolean content(ByteBuffer ref) throws IOException
        {
            if (_content==null)
                _content="";
            _content+=BufferUtil.toString(ref);
            ref.position(ref.limit());
            return false;
        }


        @Override
        public boolean earlyEOF()
        {
            return true;
        }

        @Override
        public boolean headerComplete() throws IOException
        {
            _content= null;
            return false;
        }

        @Override
        public boolean messageComplete(long contentLength) throws IOException
        {
            return true;
        }


        @Override
        public boolean parsedHeader(HttpHeader header, String name, String value) throws IOException
        {
            _hdr.add(name);
            _val.add(value);
            return false;
        }

        @Override
        public boolean startResponse(String version, int status, String reason) throws IOException
        {
            _version=version;
            _status=status;
            _reason=reason;
            return false;
        }


    }
    
    class Info implements HttpGenerator.ResponseInfo
    {
        long _contentLength=-1;
        HttpFields _fields = new HttpFields();
        boolean _head=false;
        String _reason;
        int _status;
        HttpVersion _version=HttpVersion.HTTP_1_1;
        
        
        public Info(int status)
        {
            _status=status;
        }

        @Override
        public long getContentLength()
        {
            return _contentLength;
        }

        @Override
        public HttpFields getHttpFields()
        {
            return _fields;
        }

        @Override
        public HttpVersion getHttpVersion()
        {
            return _version;
        }

        @Override
        public String getReason()
        {
            return _reason;
        }

        @Override
        public int getStatus()
        {
            return _status;
        }

        @Override
        public boolean isHead()
        {
            return _head;
        }

        public void setContentLength(long contentLength)
        {
            _contentLength = contentLength;
        }

        public void setHead(boolean head)
        {
            _head = head;
        }

        public void setReason(String reason)
        {
            _reason = reason;
        }

        public void setStatus(int status)
        {
            _status = status;
        }

        public void setHttpVersion(HttpVersion version)
        {
            _version = version;
        }
        
    }

    private static class TR implements HttpGenerator.ResponseInfo
    {
        private HttpVersion _version;
        private HttpFields _fields=new HttpFields();
        private final String _body;
        private final int _code;
        String _connection;
        int _contentLength;
        String _contentType;
        private final boolean _head;
        String _other;
        String _te;
        String _reason;

        private TR(int code,String contentType, int contentLength ,String content,boolean head)
        {
            _code=code;
            _contentType=contentType;
            _contentLength=contentLength;
            _other="value";
            _body=content;
            _head=head;
        }

        @Override
        public HttpVersion getHttpVersion()
        {
            return _version;
        }

        @Override
        public HttpFields getHttpFields()
        {
            return _fields;
        }

        @Override
        public boolean isHead()
        {
            return _head;
        }

        @Override
        public long getContentLength()
        {
            return _contentLength;
        }

        @Override
        public int getStatus()
        {
            return _code;
        }

        @Override
        public String getReason()
        {
            return _reason;
        }

        private String build(HttpGenerator.Info info, int version,HttpGenerator gen,String reason, String connection, String te, int chunks) throws Exception
        {
            String response="";
            _connection=connection;
            _te=te;
            _version=HttpVersion.fromVersion(version);
            _reason=reason;
            

            if (_contentType!=null)
                _fields.put("Content-Type",_contentType);
            if (_contentLength>=0)
                _fields.put("Content-Length",""+_contentLength);
            if (_connection!=null)
                _fields.put("Connection",_connection);
            if (_te!=null)
                _fields.put("Transfer-Encoding",_te);
            if (_other!=null)
                _fields.put("Other",_other);

            ByteBuffer content=_body==null?null:BufferUtil.toBuffer(_body);
            if (content!=null)
                content.limit(0);
            ByteBuffer header=null;
            ByteBuffer chunk=null;
            ByteBuffer buffer=null;


            while(!gen.isComplete())
            {
                // if we have unwritten content
                if (content!=null && content.position()<content.capacity())
                {
                    // if we need a new chunk
                    if (content.remaining()==0)
                    {
                        content.limit(content.capacity());
                        if (chunks-->1)
                            content.limit(content.position()+content.remaining()/2);

                        // System.err.printf("content %d %s%n",chunks,BufferUtil.toDetailString(content));
                    }
                }

                // Generate
                Action action=BufferUtil.hasContent(content)?null:Action.COMPLETE;

                /* System.err.printf("generate(%s,%s,%s,%s,%s)@%s%n",
                        BufferUtil.toSummaryString(header),
                        BufferUtil.toSummaryString(chunk),
                        BufferUtil.toSummaryString(buffer),
                        BufferUtil.toSummaryString(content),
                        action,gen.getState());*/
                HttpGenerator.Result result=gen.generate(info,header,chunk,buffer,content,action);
                /*System.err.printf("%s (%s,%s,%s,%s,%s)@%s%n",
                        result,
                        BufferUtil.toSummaryString(header),
                        BufferUtil.toSummaryString(chunk),
                        BufferUtil.toSummaryString(buffer),
                        BufferUtil.toSummaryString(content),
                        action,gen.getState());*/

                switch(result)
                {
                    case NEED_HEADER:
                        header=BufferUtil.allocate(2048);
                        break;

                    case NEED_BUFFER:
                        buffer=BufferUtil.allocate(8192);
                        break;

                    case NEED_CHUNK:
                        header=null;
                        chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                        break;

                    case FLUSH:
                        if (BufferUtil.hasContent(header))
                        {
                            response+=BufferUtil.toString(header);
                            header.position(header.limit());
                        }
                        else if (BufferUtil.hasContent(chunk))
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
                        if (BufferUtil.hasContent(header))
                        {
                            response+=BufferUtil.toString(header);
                            header.position(header.limit());
                        }
                        else if (BufferUtil.hasContent(chunk))
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

                    case OK:
                    case SHUTDOWN_OUT:
                        // TODO
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


    public final static String[] connect={null,"keep-alive","close","TE, close"};

    public final static String CONTENT="The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";

    private static final String[] headers= { "Content-Type","Content-Length","Connection","Transfer-Encoding","Other"};

    private String _content;


    private final List<String> _hdr=new ArrayList<>();

    private String _reason;




    private int _status;

    private final List<String> _val=new ArrayList<>();
    private String _version;

    private final TR[] tr =
        {
            /* 0 */  new TR(200,null,-1,null,false),
            /* 1 */  new TR(200,null,-1,CONTENT,false),
            /* 2 */  new TR(200,null,CONTENT.length(),null,true),
            /* 3 */  new TR(200,null,CONTENT.length(),CONTENT,false),
            /* 4 */  new TR(200,"text/html",-1,null,true),
            /* 5 */  new TR(200,"text/html",-1,CONTENT,false),
            /* 6 */  new TR(200,"text/html",CONTENT.length(),null,true),
            /* 7 */  new TR(200,"text/html",CONTENT.length(),CONTENT,false),
        };

    @Test
    public void testHTTP() throws Exception
    {
        Handler handler = new Handler();
        HttpParser parser=null;


        // For HTTP version
        for (int v=9;v<=11;v++)
        {
            // For each test result
            for (int r=0;r<tr.length;r++)
            {
                HttpGenerator gen = new HttpGenerator();
                
                // chunks = 1 to 3
                for (int chunks=1;chunks<=6;chunks++)
                {
                    // For none, keep-alive, close
                    for (int c=0;c<(v==11?connect.length:(connect.length-1));c++)
                    {
                        String t="v="+v+",chunks="+chunks+",connect="+connect[c]+",tr="+r+"="+tr[r];
                        // System.err.println(t);

                        gen.reset();
                        tr[r].getHttpFields().clear();

                        String response=tr[r].build(tr[r],v,gen,"OK\r\nTest",connect[c],null,chunks);

                        // System.err.println("===\n"+t+"\n"+response+(gen.isPersistent()?"...\n":"---\n"));

                        if (v==9)
                        {
                            assertFalse(t,gen.isPersistent());
                            if (tr[r]._body!=null)
                                assertEquals(t,tr[r]._body, response);
                            continue;
                        }

                        parser=new HttpParser(handler);
                        parser.setHeadResponse(tr[r]._head);

                        try
                        {
                            parser.parseNext(BufferUtil.toBuffer(response));
                        }
                        catch(IOException e)
                        {
                            if (tr[r]._body!=null)
                                throw new Exception(t,e);
                            continue;
                        }

                        if (tr[r]._body!=null)
                            assertEquals(t,tr[r]._body, this._content);

                        if (v==10)
                            assertTrue(t,gen.isPersistent() || tr[r]._contentLength>=0|| c==2 || c==0);
                        else
                            assertTrue(t,gen.isPersistent() ||  c==2 || c==3);

                        if (v>9)
                            assertEquals("OK??Test",_reason);

                        if (_content==null)
                            assertTrue(t,tr[r]._body==null);
                        else
                            assertThat(t,tr[r]._contentLength,either(equalTo(_content.length())).or(equalTo(-1)));

                    }
                }
            }
        }
    }
    @Test
    public void testResponseNoContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(8096);
        
        Info info=new Info(200);
        HttpGenerator gen = new HttpGenerator();

        info.getHttpFields().add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result
        result=gen.generate(info,null,null,null,null,Action.COMPLETE);
        assertEquals(HttpGenerator.State.COMMITTING_COMPLETING,gen.getState());
        assertEquals(HttpGenerator.Result.NEED_HEADER,result);

        result=gen.generate(info,header,null,null,null,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);

        result=gen.generate(info,null,null,null,null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());

        assertEquals(0,gen.getContentPrepared());
        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,containsString("Content-Length: 0"));

    }
    @Test
    public void testResponseWithChunkedContent() throws Exception
    {
        String body="";
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(16);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        Info info=new Info(200);
        HttpGenerator gen = new HttpGenerator();
        info.getHttpFields().add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result

        result=gen.generate(info,null,null,null,content0,null);
        assertEquals(HttpGenerator.Result.NEED_BUFFER,result);
        assertEquals(HttpGenerator.State.START,gen.getState());

        result=gen.generate(info,null,null,buffer,content0,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World! ",BufferUtil.toString(buffer));
        assertEquals(0,content0.remaining());

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.NEED_HEADER,result);
        assertEquals(HttpGenerator.State.COMMITTING,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());

        result=gen.generate(info,header,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());
        assertTrue(gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        body+=BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.NEED_CHUNK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());

        ByteBuffer chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        result=gen.generate(info,null,chunk,buffer,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("\r\n10\r\n",BufferUtil.toString(chunk));
        assertEquals(" quick brown fox",BufferUtil.toString(buffer));
        assertEquals(27,content1.remaining());
        body+=BufferUtil.toString(chunk)+BufferUtil.toString(buffer);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,chunk,buffer,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("\r\n10\r\n",BufferUtil.toString(chunk));
        assertEquals(" jumped over the",BufferUtil.toString(buffer));
        assertEquals(11,content1.remaining());
        body+=BufferUtil.toString(chunk)+BufferUtil.toString(buffer);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,chunk,buffer,content1,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("",BufferUtil.toString(chunk));
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        assertEquals(0,content1.remaining());

        result=gen.generate(info,null,chunk,buffer,null,Action.COMPLETE);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMPLETING,gen.getState());
        assertEquals("\r\nB\r\n",BufferUtil.toString(chunk));
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        body+=BufferUtil.toString(chunk)+BufferUtil.toString(buffer);
        BufferUtil.clear(chunk);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,chunk,buffer,null,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals("\r\n0\r\n\r\n",BufferUtil.toString(chunk));
        assertEquals(0,buffer.remaining());
        body+=BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);

        result=gen.generate(info,null,chunk,buffer,null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());

        assertEquals(59,gen.getContentPrepared());

        // System.err.println(head+body);

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,not(containsString("Content-Length")));
        assertThat(head,containsString("Transfer-Encoding: chunked"));
        assertTrue(head.endsWith("\r\n\r\n10\r\n"));
    }
    @Test
    public void testResponseWithKnownContent() throws Exception
    {
        String body="";
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(16);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        Info info=new Info(200);
        HttpGenerator gen = new HttpGenerator();
        info.getHttpFields().add("Last-Modified",HttpFields.__01Jan1970);
        info.getHttpFields().add("Content-Length","59");
        info.setContentLength(59);

        HttpGenerator.Result

        result=gen.generate(info,null,null,null,content0,null);
        assertEquals(HttpGenerator.Result.NEED_BUFFER,result);
        assertEquals(HttpGenerator.State.START,gen.getState());

        result=gen.generate(info,null,null,buffer,content0,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World! ",BufferUtil.toString(buffer));
        assertEquals(0,content0.remaining());

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.NEED_HEADER,result);
        assertEquals(HttpGenerator.State.COMMITTING,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());

        result=gen.generate(info,header,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());
        assertTrue(!gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        body+=BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals(" quick brown fox",BufferUtil.toString(buffer));
        assertEquals(27,content1.remaining());
        body+=BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals(" jumped over the",BufferUtil.toString(buffer));
        assertEquals(11,content1.remaining());
        body+=BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        assertEquals(0,content1.remaining());

        result=gen.generate(info,null,null,buffer,null,Action.COMPLETE);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMPLETING,gen.getState());
        assertEquals(" lazy dog. ",BufferUtil.toString(buffer));
        body+=BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,null,buffer,null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals(0,buffer.remaining());

        assertEquals(59,gen.getContentPrepared());

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
        String body="";
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        Info info=new Info(200);
        HttpGenerator gen = new HttpGenerator();
        gen.setLargeContent(8);

        info.getHttpFields().add("Last-Modified",HttpFields.__01Jan1970);
        info.getHttpFields().add("Content-Length","59");
        info.setContentLength(59);

        HttpGenerator.Result

        result=gen.generate(info,null,null,null,content0,null);
        assertEquals(HttpGenerator.Result.NEED_HEADER,result);
        assertEquals(HttpGenerator.State.COMMITTING,gen.getState());

        result=gen.generate(info,header,null,null,content0,null);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertTrue(!gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        body+=BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result=gen.generate(info,header,null,null,null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());

        result=gen.generate(info,null,null,null,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        body+=BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result=gen.generate(info,null,null,null,null,Action.COMPLETE);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());

        assertEquals(59,gen.getContentPrepared());

        // System.err.println(head+body);

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,containsString("Content-Length: 59"));
        assertThat(head,not(containsString("chunked")));
        assertTrue(head.endsWith("\r\n\r\n"));
    }
    @Test
    public void testResponseWithLargeChunkedContent() throws Exception
    {
        String body="";
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer content0=BufferUtil.toBuffer("Hello Cruel World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        Info info=new Info(200);
        HttpGenerator gen = new HttpGenerator();
        gen.setLargeContent(8);

        info.getHttpFields().add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result

        result=gen.generate(info,null,null,null,content0,null);
        assertEquals(HttpGenerator.Result.NEED_HEADER,result);
        assertEquals(HttpGenerator.State.COMMITTING,gen.getState());

        result=gen.generate(info,header,null,null,content0,null);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertTrue(gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        body+=BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result=gen.generate(info,header,null,null,content0,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());

        result=gen.generate(info,null,null,null,content1,null);
        assertEquals(HttpGenerator.Result.NEED_CHUNK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());

        ByteBuffer chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        result=gen.generate(info,null,chunk,null,content1,null);
        assertEquals(HttpGenerator.Result.FLUSH_CONTENT,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        assertEquals("\r\n2E\r\n",BufferUtil.toString(chunk));

        body+=BufferUtil.toString(chunk)+BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result=gen.generate(info,null,chunk,null,null,Action.COMPLETE);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals("\r\n0\r\n\r\n",BufferUtil.toString(chunk));
        body+=BufferUtil.toString(chunk);

        result=gen.generate(info,null,chunk,null,null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());

        assertEquals(65,gen.getContentPrepared());

        // System.err.println(head+body);

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,not(containsString("Content-Length")));
        assertThat(head,containsString("Transfer-Encoding: chunked"));
        assertTrue(head.endsWith("\r\n\r\n13\r\n"));
    }

    @Test
    public void testResponseWithSmallContent() throws Exception
    {
        String body="";
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(8096);
        ByteBuffer content=BufferUtil.toBuffer("Hello World");
        ByteBuffer content1=BufferUtil.toBuffer(". The quick brown fox jumped over the lazy dog.");
        Info info=new Info(200);
        HttpGenerator gen = new HttpGenerator();
        info.getHttpFields().add("Last-Modified",HttpFields.__01Jan1970);

        HttpGenerator.Result

        result=gen.generate(info,null,null,null,content,null);
        assertEquals(HttpGenerator.Result.NEED_BUFFER,result);
        assertEquals(HttpGenerator.State.START,gen.getState());

        result=gen.generate(info,null,null,buffer,content,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World",BufferUtil.toString(buffer));
        assertTrue(BufferUtil.isEmpty(content));

        result=gen.generate(info,null,null,buffer,content1,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.START,gen.getState());
        assertEquals("Hello World. The quick brown fox jumped over the lazy dog.",BufferUtil.toString(buffer));
        assertTrue(BufferUtil.isEmpty(content1));

        result=gen.generate(info,null,null,buffer,null,Action.COMPLETE);
        assertEquals(HttpGenerator.Result.NEED_HEADER,result);
        assertEquals(HttpGenerator.State.COMMITTING_COMPLETING,gen.getState());

        result=gen.generate(info,header,null,buffer,null,null);
        assertEquals(HttpGenerator.Result.FLUSH,result);
        assertEquals(HttpGenerator.State.COMPLETING,gen.getState());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        body+=BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.generate(info,null,null,buffer,null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.END,gen.getState());

        assertThat(head,containsString("HTTP/1.1 200 OK"));
        assertThat(head,containsString("Last-Modified: Thu, 01 Jan 1970 00?00?00 GMT"));
        assertThat(head,containsString("Content-Length: 58"));
        assertTrue(head.endsWith("\r\n\r\n"));

        assertEquals("Hello World. The quick brown fox jumped over the lazy dog.",body);

        assertEquals(58,gen.getContentPrepared());
    }
}
