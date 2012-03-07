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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.matchers.JUnitMatchers.containsString;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.swing.text.View;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.matchers.JUnitMatchers;

public class HttpGeneratorClientTest
{
    public final static String CONTENT="The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";
    public final static String[] connect={null,"keep-alive","close"};


    @Test
    public void testRequestNoContent() throws Exception
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
        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        assertThat(out,containsString("GET /index.html HTTP/1.1"));
        assertThat(out,not(containsString("Content-Length")));
  
        result=gen.complete(null,null);
        assertEquals(HttpGenerator.Result.OK,result);
        
        
        assertEquals(HttpGenerator.State.END,gen.getState());
        assertEquals(0,gen.getContentWritten());    }
    
    @Test
    public void testRequestWithSmallContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(8096);
        ByteBuffer content=BufferUtil.toBuffer("Hello World");
        ByteBuffer content1=BufferUtil.toBuffer(". The quick brown fox jumped over the lazy dog.");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setRequest("POST","/index.html");
        fields.add("Host","something");
        fields.add("User-Agent","test");

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
        
        assertThat(head,containsString("POST /index.html HTTP/1.1"));
        assertThat(head,containsString("Host: something"));
        assertThat(head,containsString("Content-Length: 58"));
        assertTrue(head.endsWith("\r\n\r\n"));
        
        assertEquals("Hello World. The quick brown fox jumped over the lazy dog.",body);
        
        assertEquals(58,gen.getContentWritten());    
    }

    @Test
    public void testRequestWithChunkedContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(16);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setRequest("POST","/index.html");
        fields.add("Host","something");
        fields.add("User-Agent","test");

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
        assertEquals(HttpGenerator.State.COMMITTING,gen.getState());
        assertEquals("Hello World! The",BufferUtil.toString(buffer));
        assertEquals(43,content1.remaining());
        assertTrue(gen.isChunking());

        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        String body = BufferUtil.toString(buffer);
        BufferUtil.clear(buffer);

        result=gen.commit(fields,header,buffer,content1,false);
        assertEquals(HttpGenerator.Result.OK,result);
        assertEquals(HttpGenerator.State.COMMITTED,gen.getState());
        
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

        assertThat(head,containsString("POST /index.html HTTP/1.1"));
        assertThat(head,containsString("Host: something"));
        assertThat(head,not(containsString("Content-Length")));
        assertThat(head,containsString("Transfer-Encoding: chunked"));
        assertTrue(head.endsWith("\r\n\r\n10\r\n"));
    }

    @Test
    public void testRequestWithLargeChunkedContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer content0=BufferUtil.toBuffer("Hello Cruel World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();
        gen.setLargeContent(8);

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setRequest("POST","/index.html");
        fields.add("Host","something");
        fields.add("User-Agent","test");

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

        assertThat(head,containsString("POST /index.html HTTP/1.1"));
        assertThat(head,containsString("Host: something"));
        assertThat(head,not(containsString("Content-Length")));
        assertThat(head,containsString("Transfer-Encoding: chunked"));
        assertTrue(head.endsWith("\r\n\r\n13\r\n"));
    }


    @Test
    public void testRequestWithKnownContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer buffer=BufferUtil.allocate(16);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setRequest("POST","/index.html");
        fields.add("Host","something");
        fields.add("User-Agent","test");
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

        assertThat(head,containsString("POST /index.html HTTP/1.1"));
        assertThat(head,containsString("Host: something"));
        assertThat(head,containsString("Content-Length: 59"));
        assertThat(head,not(containsString("chunked")));
        assertTrue(head.endsWith("\r\n\r\n"));
    }

    @Test
    public void testRequestWithKnownLargeContent() throws Exception
    {
        ByteBuffer header=BufferUtil.allocate(4096);
        ByteBuffer content0=BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1=BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpFields fields = new HttpFields();
        HttpGenerator gen = new HttpGenerator();
        gen.setLargeContent(8);

        gen.setVersion(HttpVersion.HTTP_1_1);
        gen.setRequest("POST","/index.html");
        fields.add("Host","something");
        fields.add("User-Agent","test");
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

        assertThat(head,containsString("POST /index.html HTTP/1.1"));
        assertThat(head,containsString("Host: something"));
        assertThat(head,containsString("Content-Length: 59"));
        assertThat(head,not(containsString("chunked")));
        assertTrue(head.endsWith("\r\n\r\n"));
    }
    
}
