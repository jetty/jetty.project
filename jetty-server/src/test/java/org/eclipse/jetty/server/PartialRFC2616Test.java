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

/*
 * Created on 9/01/2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 */
@RunWith(AdvancedRunner.class)
public class PartialRFC2616Test
{
    private Server server;
    private LocalConnector connector;

    @Before
    public void init() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setIdleTimeout(10000);
        server.addConnector(connector);

        ContextHandler vcontext=new ContextHandler();
        vcontext.setContextPath("/");
        vcontext.setVirtualHosts(new String[]
        { "VirtualHost" });
        vcontext.setHandler(new DumpHandler("Virtual Dump"));

        ContextHandler context=new ContextHandler();
        context.setContextPath("/");
        context.setHandler(new DumpHandler());

        HandlerCollection collection=new HandlerCollection();
        collection.setHandlers(new Handler[]
        { vcontext, context });

        server.setHandler(collection);

        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void test3_3()
    {
        try
        {
            HttpFields fields=new HttpFields();

            fields.put("D1","Sun, 6 Nov 1994 08:49:37 GMT");
            fields.put("D2","Sunday, 6-Nov-94 08:49:37 GMT");
            fields.put("D3","Sun Nov  6 08:49:37 1994");
            Date d1=new Date(fields.getDateField("D1"));
            Date d2=new Date(fields.getDateField("D2"));
            Date d3=new Date(fields.getDateField("D3"));

            assertEquals("3.3.1 RFC 822 RFC 850",d2,d1);
            assertEquals("3.3.1 RFC 850 ANSI C",d3,d2);

            fields.putDateField("Date",d1.getTime());
            assertEquals("3.3.1 RFC 822 preferred","Sun, 06 Nov 1994 08:49:37 GMT",fields.get("Date"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    @Test
    public void test3_6_a() throws Exception
    {
        int offset=0;
        // Chunk last
        String response = connector.getResponse(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked,identity\n" +
                        "Content-Type: text/plain\n" +
                        "\015\012" +
                        "5;\015\012" +
                        "123\015\012\015\012" +
                        "0;\015\012\015\012");
        checkContains(response,offset,"HTTP/1.1 400 Bad","Chunked last");
    }

    @Test
    public void test3_6_b() throws Exception
    {
        String response;
        int offset=0;
        // Chunked
        LocalEndPoint endp=connector.executeRequest(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked\n" +
                        "Content-Type: text/plain\n" +
                        "\n" +
                        "2;\n" +
                        "12\n" +
                        "3;\n" +
                        "345\n" +
                        "0;\n\n" +

                        "GET /R2 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked\n" +
                        "Content-Type: text/plain\n" +
                        "\n" +
                        "4;\n" +
                        "6789\n" +
                        "5;\n" +
                        "abcde\n" +
                        "0;\n\n" +

                        "GET /R3 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Connection: close\n" +
                        "\n");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
        offset=checkContains(response,offset,"12345","3.6.1 Chunking");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
        offset=checkContains(response,offset,"6789abcde","3.6.1 Chunking");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"/R3","3.6.1 Chunking");
    }

    @Test
    public void test3_6_c() throws Exception
    {
        String response;
        int offset=0;
        LocalEndPoint endp=connector.executeRequest(
                "POST /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked\n" +
                        "Content-Type: text/plain\n" +
                        "\n" +
                        "3;\n" +
                        "fgh\n" +
                        "3;\n" +
                        "Ijk\n" +
                        "0;\n\n" +

                        "POST /R2 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked\n" +
                        "Content-Type: text/plain\n" +
                        "\n" +
                        "4;\n" +
                        "lmno\n" +
                        "5;\n" +
                        "Pqrst\n" +
                        "0;\n\n" +

                        "GET /R3 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Connection: close\n" +
                        "\n");
        offset=0;
        response = endp.getResponse();
        checkNotContained(response,"HTTP/1.1 100","3.6.1 Chunking");
        offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
        offset=checkContains(response,offset,"fghIjk","3.6.1 Chunking");
        offset=0;
        response = endp.getResponse();
        checkNotContained(response,"HTTP/1.1 100","3.6.1 Chunking");
        offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
        offset=checkContains(response,offset,"lmnoPqrst","3.6.1 Chunking");
        offset=0;
        response = endp.getResponse();
        checkNotContained(response,"HTTP/1.1 100","3.6.1 Chunking");
        offset=checkContains(response,offset,"/R3","3.6.1 Chunking");
    }

    @Test
    public void test3_6_d() throws Exception
    {
        String response;
        int offset=0;
        // Chunked and keep alive
        LocalEndPoint endp=connector.executeRequest(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked\n" +
                        "Content-Type: text/plain\n" +
                        "Connection: keep-alive\n" +
                        "\n" +
                        "3;\n" +
                        "123\n" +
                        "3;\n" +
                        "456\n" +
                        "0;\n\n" +

                        "GET /R2 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Connection: close\n" +
                        "\n");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking")+10;
        offset=checkContains(response,offset,"123456","3.6.1 Chunking");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"/R2","3.6.1 Chunking")+10;
    }

    @Test
    public void test3_9() throws Exception
    {
        HttpFields fields=new HttpFields();

        fields.put("Q","bbb;q=0.5,aaa,ccc;q=0.002,d;q=0,e;q=0.0001,ddd;q=0.001,aa2,abb;q=0.7");
        Enumeration<String> qualities=fields.getValues("Q",", \t");
        List<String> list=HttpFields.qualityList(qualities);
        assertEquals("Quality parameters","aaa",HttpFields.valueParameters(list.get(0),null));
        assertEquals("Quality parameters","aa2",HttpFields.valueParameters(list.get(1),null));
        assertEquals("Quality parameters","abb",HttpFields.valueParameters(list.get(2),null));
        assertEquals("Quality parameters","bbb",HttpFields.valueParameters(list.get(3),null));
        assertEquals("Quality parameters","ccc",HttpFields.valueParameters(list.get(4),null));
        assertEquals("Quality parameters","ddd",HttpFields.valueParameters(list.get(5),null));
    }

    @Test
    public void test4_4_2() throws Exception
    {
        String response;
        int offset=0;
        // If _content length not used, second request will not be read.
        LocalEndPoint endp=connector.executeRequest(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: identity\n" +
                        "Content-Type: text/plain\n" +
                        "Content-Length: 5\n" +
                        "\n" +
                        "123\015\012" +

                        "GET /R2 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: other\n" +
                        "Connection: close\n" +
                        "\n");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200 OK","2. identity")+10;
        offset=checkContains(response,offset,"/R1","2. identity")+3;
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200 OK","2. identity")+10;
        offset=checkContains(response,offset,"/R2","2. identity")+3;
    }

    @Test
    public void test4_4_3() throws Exception
    {
        // _content length is ignored, as chunking is used. If it is
        // not ignored, the second request wont be seen.
        int offset=0;
        String response;
        LocalEndPoint endp=connector.executeRequest(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Transfer-Encoding: chunked\n" +
                        "Content-Type: text/plain\n" +
                        "Content-Length: 100\n" +
                        "\n" +
                        "3;\n" +
                        "123\n" +
                        "3;\n" +
                        "456\n" +
                        "0;\n" +
                        "\n" +

                        "GET /R2 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Connection: close\n" +
                        "Content-Type: text/plain\n" +
                        "Content-Length: 6\n" +
                        "\n" +
                        "abcdef");
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200 OK","3. ignore c-l")+1;
        offset=checkContains(response,offset,"/R1","3. ignore c-l")+1;
        offset=checkContains(response,offset,"123456","3. ignore c-l")+1;
        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200 OK","3. ignore c-l")+1;
        offset=checkContains(response,offset,"/R2","3. _content-length")+1;
        offset=checkContains(response,offset,"abcdef","3. _content-length")+1;
    }

    @Test
    public void test4_4_4() throws Exception
    {
        // No _content length
        assertTrue("Skip 411 checks as IE breaks this rule",true);
        // offset=0; connector.reopen();
        // response=connector.getResponse("GET /R2 HTTP/1.1\n"+
        // "Host: localhost\n"+
        // "Content-Type: text/plain\n"+
        // "Connection: close\n"+
        // "\n"+
        // "123456");
        // offset=checkContains(response,offset,
        // "HTTP/1.1 411 ","411 length required")+10;
        // offset=0; connector.reopen();
        // response=connector.getResponse("GET /R2 HTTP/1.0\n"+
        // "Content-Type: text/plain\n"+
        // "\n"+
        // "123456");
        // offset=checkContains(response,offset,
        // "HTTP/1.0 411 ","411 length required")+10;

    }

    @Test
    public void test5_2_1() throws Exception
    {
        // Default Host
        int offset=0;
        String response = connector.getResponse("GET http://VirtualHost:8888/path/R1 HTTP/1.1\n" + "Host: wronghost\n" + "Connection: close\n" + "\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","Virtual host")+1;
        offset=checkContains(response,offset,"Virtual Dump","Virtual host")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","Virtual host")+1;
        offset=checkContains(response,offset,"servername=VirtualHost","Virtual host")+1;
    }

    @Test
    public void test5_2_2() throws Exception
    {
        // Default Host
        int offset=0;
        String response = connector.getResponse("GET /path/R1 HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","Default host")+1;
        offset=checkContains(response,offset,"Dump HttpHandler","Default host")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","Default host")+1;

        // Virtual Host
        offset=0;
        response=connector.getResponse("GET /path/R2 HTTP/1.1\n"+"Host: VirtualHost\n"+"Connection: close\n"+"\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","Default host")+1;
        offset=checkContains(response,offset,"Virtual Dump","virtual host")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R2","Default host")+1;
    }

    @Test
    public void test5_2() throws Exception
    {
        // Virtual Host
        int offset=0;
        String response = connector.getResponse("GET /path/R1 HTTP/1.1\n" + "Host: VirtualHost\n" + "Connection: close\n" + "\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","2. virtual host field")+1;
        offset=checkContains(response,offset,"Virtual Dump","2. virtual host field")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","2. virtual host field")+1;

        // Virtual Host case insensitive
        offset=0;
        response=connector.getResponse("GET /path/R1 HTTP/1.1\n"+"Host: ViRtUalhOst\n"+"Connection: close\n"+"\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","2. virtual host field")+1;
        offset=checkContains(response,offset,"Virtual Dump","2. virtual host field")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","2. virtual host field")+1;

        // Virtual Host
        offset=0;
        response=connector.getResponse("GET /path/R1 HTTP/1.1\n"+"\n");
        offset=checkContains(response,offset,"HTTP/1.1 400","3. no host")+1;
    }

    @Test
    public void test8_1() throws Exception
    {
        int offset=0;
        String response = connector.getResponse("GET /R1 HTTP/1.1\n" + "Host: localhost\n" + "\n", 250, TimeUnit.MILLISECONDS);
        offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","8.1.2 default")+10;
        checkContains(response,offset,"Content-Length: ","8.1.2 default");

        LocalEndPoint endp=connector.executeRequest("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"\n"+
            "GET /R2 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n"+
            "GET /R3 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");

        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","8.1.2 default")+1;
        offset=checkContains(response,offset,"/R1","8.1.2 default")+1;

        offset=0;
        response = endp.getResponse();
        offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","8.1.2.2 pipeline")+11;
        offset=checkContains(response,offset,"Connection: close","8.1.2.2 pipeline")+1;
        offset=checkContains(response,offset,"/R2","8.1.2.1 close")+3;

        offset=0;
        response = endp.getResponse();
        assertThat(response,nullValue());

    }

    @Test
    public void test10_4_18() throws Exception
    {
        // Expect Failure
        int offset=0;
        String response = connector.getResponse(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Expect: unknown\n" +
                        "Content-Type: text/plain\n" +
                        "Content-Length: 8\n" +
                        "\n");
        offset=checkContains(response,offset,"HTTP/1.1 417","8.2.3 expect failure")+1;
    }

    @Test
    public void test8_2_3_dash5() throws Exception
    {
        // Expect with body: client sends the content right away, we should not send 100-Continue
        int offset=0;
        String response = connector.getResponse(
                "GET /R1 HTTP/1.1\n" +
                        "Host: localhost\n" +
                        "Expect: 100-continue\n" +
                        "Content-Type: text/plain\n" +
                        "Content-Length: 8\n" +
                        "Connection: close\n" +
                        "\n" +
                        "123456\015\012");
        checkNotContained(response, offset, "HTTP/1.1 100 ", "8.2.3 expect 100");
        offset=checkContains(response,offset,"HTTP/1.1 200 OK","8.2.3 expect with body")+1;
    }

    @Test
    public void test8_2_3() throws Exception
    {
        int offset=0;
        // Expect 100
        LocalConnector.LocalEndPoint endp =connector.executeRequest("GET /R1 HTTP/1.1\n"+
                "Host: localhost\n"+
                "Connection: close\n"+
                "Expect: 100-continue\n"+
                "Content-Type: text/plain\n"+
                "Content-Length: 8\n"+
                "\n");
        String infomational= endp.getResponse();
        offset=checkContains(infomational,offset,"HTTP/1.1 100 ","8.2.3 expect 100")+1;
        checkNotContained(infomational,offset,"HTTP/1.1 200","8.2.3 expect 100");

        endp.addInput("654321\015\012");

        String response= endp.getResponse();
        offset=0;
        offset=checkContains(response,offset,"HTTP/1.1 200","8.2.3 expect 100")+1;
        offset=checkContains(response,offset,"654321","8.2.3 expect 100")+1;
    }

    @Test
    public void test8_2_4() throws Exception
    {
        // Expect 100 not sent
        int offset=0;
        String response = connector.getResponse("GET /R1?error=401 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Expect: 100-continue\n" +
                "Content-Type: text/plain\n" +
                "Content-Length: 8\n" +
                "\n");
        checkNotContained(response,offset,"HTTP/1.1 100","8.2.3 expect 100");
        offset=checkContains(response,offset,"HTTP/1.1 401 ","8.2.3 expect 100")+1;
        offset=checkContains(response,offset,"Connection: close","8.2.3 expect 100")+1;
    }

    @Test
    public void test9_2() throws Exception
    {
         int offset=0;

         String response=connector.getResponse("OPTIONS * HTTP/1.1\n"+
             "Connection: close\n"+
             "Host: localhost\n"+
             "\n");
         offset=checkContains(response,offset, "HTTP/1.1 200","200")+1;

         offset=0;
         response=connector.getResponse("GET * HTTP/1.1\n"+
             "Connection: close\n"+
             "Host: localhost\n"+
             "\n");
         offset=checkContains(response,offset, "HTTP/1.1 400","400")+1;
    }

    @Test
    public void test9_4()
    {
        try
        {
            String get=connector.getResponse("GET /R1 HTTP/1.0\n"+"Host: localhost\n"+"\n");

            checkContains(get,0,"HTTP/1.1 200","GET");
            checkContains(get,0,"Content-Type: text/html","GET _content");
            checkContains(get,0,"<html>","GET body");

            String head=connector.getResponse("HEAD /R1 HTTP/1.0\n"+"Host: localhost\n"+"\n");
            checkContains(head,0,"HTTP/1.1 200","HEAD");
            checkContains(head,0,"Content-Type: text/html","HEAD _content");
            assertEquals("HEAD no body",-1,head.indexOf("<html>"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }



    @Test
    public void test14_23() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpParser.class))
        {
            int offset=0;
            String response = connector.getResponse("GET /R1 HTTP/1.0\n" + "Connection: close\n" + "\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","200")+1;

            offset=0;
            response=connector.getResponse("GET /R1 HTTP/1.1\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 400","400")+1;

            offset=0;
            response=connector.getResponse("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","200")+1;

            offset=0;
            response=connector.getResponse("GET /R1 HTTP/1.1\n"+"Host:\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","200")+1;
        }
    }

    @Test
    public void test19_6()
    {
        try
        {
            int offset=0;
            String response = connector.getResponse("GET /R1 HTTP/1.0\n" + "\n");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 default close")+10;
            checkNotContained(response,offset,"Connection: close","19.6.2 not assumed");

            LocalEndPoint endp=connector.executeRequest(
                "GET /R1 HTTP/1.0\n"+"Host: localhost\n"+"Connection: keep-alive\n"+"\n"+
                "GET /R2 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n"+
                "GET /R3 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n");

            offset=0;
            response = endp.getResponse();
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"Connection: keep-alive","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"<html>","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"/R1","19.6.2 Keep-alive 1")+1;

            offset=0;
            response = endp.getResponse();
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 2")+11;
            offset=checkContains(response,offset,"/R2","19.6.2 Keep-alive close")+3;

            offset=0;
            response = endp.getResponse();
            assertThat("19.6.2 closed",response,nullValue());

            offset=0;
            endp=connector.executeRequest(
                "GET /R1 HTTP/1.0\n"+"Host: localhost\n"+"Connection: keep-alive\n"+"Content-Length: 10\n"+"\n"+"1234567890\n"+
                "GET /RA HTTP/1.0\n"+"Host: localhost\n"+"Connection: keep-alive\n"+"Content-Length: 10\n"+"\n"+"ABCDEFGHIJ\n"+
                "GET /R2 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n"+
                "GET /R3 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            
            offset=0;
            response = endp.getResponse();
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"Connection: keep-alive","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"<html>","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"1234567890","19.6.2 Keep-alive 1")+1;

            offset=0;
            response = endp.getResponse();
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"Connection: keep-alive","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"<html>","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"ABCDEFGHIJ","19.6.2 Keep-alive 1")+1;

            offset=0;
            response = endp.getResponse();
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 2")+11;
            offset=checkContains(response,offset,"/R2","19.6.2 Keep-alive close")+3;

            offset=0;
            response = endp.getResponse();
            assertThat("19.6.2 closed",response,nullValue());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    private int checkContains(String s, int offset, String c, String test)
    {
        Assert.assertThat(test,s.substring(offset),containsString(c));
        return s.indexOf(c,offset);
    }

    private void checkNotContained(String s, int offset, String c, String test)
    {
        Assert.assertThat(test,s.substring(offset),not(containsString(c)));
    }

    private void checkNotContained(String s, String c, String test)
    {
        checkNotContained(s,0,c,test);
    }

}
