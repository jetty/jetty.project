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

/*
 * Created on 9/01/2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package org.eclipse.jetty.server;

import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;

/**
 *
 *
 */
public class RFC2616Test extends TestCase
{
    Server server=new Server();
    LocalConnector connector=new LocalConnector();

    /**
     * Constructor for RFC2616Test.
     *
     * @param arg0
     */
    public RFC2616Test(String arg0)
    {
        super(arg0);
        server.setConnectors(new Connector[]
        { connector });

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
    }

    /*
     * @see TestCase#setUp()
     */
    protected void setUp() throws Exception
    {
        super.setUp();

        server.start();
    }

    /*
     * @see TestCase#tearDown()
     */
    protected void tearDown() throws Exception
    {
        super.tearDown();
        server.stop();
    }

    /* --------------------------------------------------------------- */
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
            assertEquals("3.3.1 RFC 822 preferred","Sun, 06 Nov 1994 08:49:37 GMT",fields.getStringField("Date"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    /* --------------------------------------------------------------- */
    public void test3_6()
    {

        String response=null;
        try
        {
            int offset=0;

            // Chunk last
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked,identity\n"+"Content-Type: text/plain\n"
                    +"\015\012"+"5;\015\012"+"123\015\012\015\012"+"0;\015\012\015\012");
            checkContains(response,offset,"HTTP/1.1 400 Bad","Chunked last");

            // Chunked
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked\n"+"Content-Type: text/plain\n"+"\n"+"2;\n"
                    +"12\n"+"3;\n"+"345\n"+"0;\n\n"+

                    "GET /R2 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked\n"+"Content-Type: text/plain\n"+"\n"+"4;\n"+"6789\n"+"5;\n"+"abcde\n"
                    +"0;\n\n"+

                    "GET /R3 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
            offset=checkContains(response,offset,"12345","3.6.1 Chunking");
            offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
            offset=checkContains(response,offset,"6789abcde","3.6.1 Chunking");
            offset=checkContains(response,offset,"/R3","3.6.1 Chunking");

            // Chunked
            offset=0;
            response=connector.getResponses("POST /R1 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked\n"+"Content-Type: text/plain\n"+"\n"+"3;\n"
                    +"fgh\n"+"3;\n"+"Ijk\n"+"0;\n\n"+

                    "POST /R2 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked\n"+"Content-Type: text/plain\n"+"\n"+"4;\n"+"lmno\n"+"5;\n"+"Pqrst\n"
                    +"0;\n\n"+

                    "GET /R3 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            checkNotContained(response,"HTTP/1.1 100","3.6.1 Chunking");
            offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
            offset=checkContains(response,offset,"fghIjk","3.6.1 Chunking");
            offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking");
            offset=checkContains(response,offset,"lmnoPqrst","3.6.1 Chunking");
            offset=checkContains(response,offset,"/R3","3.6.1 Chunking");

            // Chunked and keep alive
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked\n"+"Content-Type: text/plain\n"
                    +"Connection: keep-alive\n"+"\n"+"3;\n"+"123\n"+"3;\n"+"456\n"+"0;\n\n"+

                    "GET /R2 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","3.6.1 Chunking")+10;
            offset=checkContains(response,offset,"123456","3.6.1 Chunking");
            offset=checkContains(response,offset,"/R2","3.6.1 Chunking")+10;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
            if (response!=null)
                System.err.println(response);
        }
    }

    /* --------------------------------------------------------------- */
    public void test3_9()
    {
        try
        {
            HttpFields fields=new HttpFields();

            fields.put("Q","bbb;q=0.5,aaa,ccc;q=0.002,d;q=0,e;q=0.0001,ddd;q=0.001,aa2,abb;q=0.7");
            Enumeration qualities=fields.getValues("Q",", \t");
            List list=HttpFields.qualityList(qualities);
            assertEquals("Quality parameters","aaa",HttpFields.valueParameters(list.get(0).toString(),null));
            assertEquals("Quality parameters","aa2",HttpFields.valueParameters(list.get(1).toString(),null));
            assertEquals("Quality parameters","abb",HttpFields.valueParameters(list.get(2).toString(),null));
            assertEquals("Quality parameters","bbb",HttpFields.valueParameters(list.get(3).toString(),null));
            assertEquals("Quality parameters","ccc",HttpFields.valueParameters(list.get(4).toString(),null));
            assertEquals("Quality parameters","ddd",HttpFields.valueParameters(list.get(5).toString(),null));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    /* --------------------------------------------------------------- */
    public void test4_4()
    {
        try
        {

            String response;
            int offset=0;

            // 2
            // If _content length not used, second request will not be read.
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: identity\n"+"Content-Type: text/plain\n"
                    +"Content-Length: 5\n"+"\n"+"123\015\012"+

                    "GET /R2 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK","2. identity")+10;
            offset=checkContains(response,offset,"/R1","2. identity")+3;
            offset=checkContains(response,offset,"HTTP/1.1 200 OK","2. identity")+10;
            offset=checkContains(response,offset,"/R2","2. identity")+3;

            // 3
            // _content length is ignored, as chunking is used. If it is
            // not ignored, the second request wont be seen.
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Transfer-Encoding: chunked\n"+"Content-Type: text/plain\n"
                    +"Content-Length: 100\n"+"\n"+"3;\n"+"123\n"+"3;\n"+"456\n"+"0;\n"+"\n"+

                    "GET /R2 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"Content-Type: text/plain\n"+"Content-Length: 6\n"+"\n"+"123456");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK","3. ignore c-l")+1;
            offset=checkContains(response,offset,"/R1","3. ignore c-l")+1;
            offset=checkContains(response,offset,"123456","3. ignore c-l")+1;
            offset=checkContains(response,offset,"HTTP/1.1 200 OK","3. ignore c-l")+1;
            offset=checkContains(response,offset,"/R2","3. _content-length")+1;
            offset=checkContains(response,offset,"123456","3. _content-length")+1;

            // No _content length
            assertTrue("Skip 411 checks as IE breaks this rule",true);
            // offset=0; connector.reopen();
            // response=connector.getResponses("GET /R2 HTTP/1.1\n"+
            // "Host: localhost\n"+
            // "Content-Type: text/plain\n"+
            // "Connection: close\n"+
            // "\n"+
            // "123456");
            // offset=checkContains(response,offset,
            // "HTTP/1.1 411 ","411 length required")+10;
            // offset=0; connector.reopen();
            // response=connector.getResponses("GET /R2 HTTP/1.0\n"+
            // "Content-Type: text/plain\n"+
            // "\n"+
            // "123456");
            // offset=checkContains(response,offset,
            // "HTTP/1.0 411 ","411 length required")+10;

        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    /* --------------------------------------------------------------- */
    public void test5_2() throws Exception
    {
        String response;
        int offset=0;

        // Default Host
        offset=0;
        response=connector.getResponses("GET /path/R1 HTTP/1.1\n"+"Host: localhost\n"+"\n");

        offset=checkContains(response,offset,"HTTP/1.1 200","Default host")+1;
        offset=checkContains(response,offset,"Dump HttpHandler","Default host")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","Default host")+1;

        // Virtual Host
        offset=0;
        response=connector.getResponses("GET /path/R1 HTTP/1.1\n"+"Host: VirtualHost\n"+"\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","2. virtual host field")+1;
        offset=checkContains(response,offset,"Virtual Dump","2. virtual host field")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","2. virtual host field")+1;

        // Virtual Host case insensitive
        offset=0;
        response=connector.getResponses("GET /path/R1 HTTP/1.1\n"+"Host: ViRtUalhOst\n"+"\n");
        offset=checkContains(response,offset,"HTTP/1.1 200","2. virtual host field")+1;
        offset=checkContains(response,offset,"Virtual Dump","2. virtual host field")+1;
        offset=checkContains(response,offset,"pathInfo=/path/R1","2. virtual host field")+1;

        // Virtual Host
        offset=0;
        response=connector.getResponses("GET /path/R1 HTTP/1.1\n"+"\n");
        offset=checkContains(response,offset,"HTTP/1.1 400","3. no host")+1;

    }

    /* --------------------------------------------------------------- */
    public void test8_1()
    {
        try
        {
            String response;
            int offset=0;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","8.1.2 default")+10;
            checkContains(response,offset,"Content-Length: ","8.1.2 default");

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"\n"+

            "GET /R2 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n"+

            "GET /R3 HTTP/1.1\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","8.1.2 default")+1;
            offset=checkContains(response,offset,"/R1","8.1.2 default")+1;

            assertEquals("8.1.2.1 close",-1,response.indexOf("/R3"));

            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","8.1.2.2 pipeline")+11;
            offset=checkContains(response,offset,"Connection: close","8.1.2.2 pipeline")+1;
            offset=checkContains(response,offset,"/R2","8.1.2.1 close")+3;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    /* --------------------------------------------------------------- */
    public void test8_2()
    {
        try
        {
            String response;
            int offset=0;
            // No Expect 100
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                            "Host: localhost\n"+
                                            "Content-Type: text/plain\n"+
                                            "Content-Length: 8\n"+
                                            "\n",true);

            assertTrue("8.2.3 no expect 100",response==null || response.length()==0);
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                            "Host: localhost\n"+
                                            "Content-Type: text/plain\n"+
                                            "Content-Length: 8\n"+
                                            "\n"+
                                            "AbCdEf\015\012",true);
            offset=checkContains(response,offset,"HTTP/1.1 200","8.2.3 no expect 100")+1;
            offset=checkContains(response,offset,"AbCdEf","8.2.3 no expect 100")+1;

            // Expect Failure
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Expect: unknown\n"+"Content-Type: text/plain\n"+"Content-Length: 8\n"
                    +"\n");
            offset=checkContains(response,offset,"HTTP/1.1 417","8.2.3 expect failure")+1;

            // Expect with body
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"Expect: 100-continue\n"+"Content-Type: text/plain\n"
                    +"Content-Length: 8\n"+"Connection: close\n"+"\n"+"123456\015\012");
            checkNotContained(response,offset,"HTTP/1.1 100 ","8.2.3 expect 100");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK","8.2.3 expect with body")+1;

            // Expect 100
            ((StdErrLog)Log.getLog()).setHideStacks(true);
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                            "Host: localhost\n"+
                                            "Connection: close\n"+
                                            "Expect: 100-continue\n"+
                                            "Content-Type: text/plain\n"+
                                            "Content-Length: 8\n"+
                                            "\n",true);
            offset=checkContains(response,offset,"HTTP/1.1 100 ","8.2.3 expect 100")+1;
            checkNotContained(response,offset,"HTTP/1.1 200","8.2.3 expect 100");
            /* can't test this with localconnector.
            response=connector.getResponses("654321\015\012");
            offset=checkContains(response,offset,"HTTP/1.1 200","8.2.3 expect 100")+1;
            offset=checkContains(response,offset,"654321","8.2.3 expect 100")+1;
            */

            ((StdErrLog)Log.getLog()).setHideStacks(false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
        finally
        {
        }
    }

    /* --------------------------------------------------------------- */
    public void test9_2()
    {
        // TODO
        /*
         * try { TestListener listener = new TestListener(); String response;
         * int offset=0; connector.reopen();
         *  // Default Host offset=0; connector.reopen();
         * response=connector.getResponses("OPTIONS * HTTP/1.1\n"+ "Connection:
         * close\n"+ "Host: localhost\n"+ "\n");
         * offset=checkContains(response,offset, "HTTP/1.1 200","200")+1;
         * offset=checkContains(response,offset, "Allow: GET, HEAD, POST, PUT,
         * DELETE, MOVE, OPTIONS, TRACE","Allow")+1;
         *  } catch(Exception e) { e.printStackTrace(); assertTrue(false); }
         */
    }

    /* --------------------------------------------------------------- */
    public void test9_4()
    {
        try
        {
            String get=connector.getResponses("GET /R1 HTTP/1.0\n"+"Host: localhost\n"+"\n");

            checkContains(get,0,"HTTP/1.1 200","GET");
            checkContains(get,0,"Content-Type: text/html","GET _content");
            checkContains(get,0,"<html>","GET body");

            String head=connector.getResponses("HEAD /R1 HTTP/1.0\n"+"Host: localhost\n"+"\n");
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

    /* --------------------------------------------------------------- */
    public void test9_8()
    {
        // TODO
        /*
         * try { TestListener listener = new TestListener(); String response;
         * int offset=0; connector.reopen();
         *  // Default Host offset=0; connector.reopen();
         * response=connector.getResponses("TRACE /path HTTP/1.1\n"+ "Host:
         * localhost\n"+ "Connection: close\n"+ "\n");
         * offset=checkContains(response,offset, "HTTP/1.1 200","200")+1;
         * offset=checkContains(response,offset, "Content-Type: message/http",
         * "message/http")+1; offset=checkContains(response,offset, "TRACE /path
         * HTTP/1.1\r\n"+ "Host: localhost\r\n", "Request"); } catch(Exception
         * e) { e.printStackTrace(); assertTrue(false); }
         */
    }

    /* --------------------------------------------------------------- */
    public void test10_2_7()
    {
        // TODO
        /*
         *
         * try { TestListener listener = new TestListener(); String response;
         * int offset=0; connector.reopen();
         *  // check to see if corresponging GET w/o range would return // a)
         * ETag // b) Content-Location // these same headers will be required
         * for corresponding // sub range requests
         *
         * response=connector.getResponses("GET /" +
         * TestRFC2616.testFiles[0].name + " HTTP/1.1\n"+ "Host: localhost\n"+
         * "Connection: close\n"+ "\n");
         *
         * boolean noRangeHasContentLocation =
         * (response.indexOf("\r\nContent-Location: ") != -1);
         *
         *  // now try again for the same resource but this time WITH range
         * header
         *
         * response=connector.getResponses("GET /" +
         * TestRFC2616.testFiles[0].name + " HTTP/1.1\n"+ "Host: localhost\n"+
         * "Connection: close\n"+ "Range: bytes=1-3\n"+ "\n");
         *
         * offset=0; connector.reopen(); offset=checkContains(response,offset,
         * "HTTP/1.1 206 Partial Content\r\n", "1. proper 206 status code");
         * offset=checkContains(response,offset, "Content-Type: text/plain", "2.
         * _content type") + 2; offset=checkContains(response,offset,
         * "Last-Modified: " + TestRFC2616.testFiles[0].modDate + "\r\n", "3.
         * correct resource mod date");
         *  // if GET w/o range had Content-Location, then the corresponding //
         * response for the a GET w/ range must also have that same header
         *
         * offset=checkContains(response,offset, "Content-Range: bytes 1-3/26",
         * "4. _content range") + 2;
         *
         * if (noRangeHasContentLocation) {
         * offset=checkContains(response,offset, "Content-Location: ", "5.
         * Content-Location header as with 200"); } else { // no need to check
         * for Conten-Location header in 206 response // spec does not require
         * existence or absence if these want any // header for the get w/o
         * range }
         *
         * String expectedData = TestRFC2616.testFiles[0].data.substring(1,
         * 3+1); offset=checkContains(response,offset, expectedData, "6.
         * subrange data: \"" + expectedData + "\""); } catch(Exception e) {
         * e.printStackTrace(); assertTrue(false); }
         *
         */
    }

    /* --------------------------------------------------------------- */
    public void test10_3()
    {
        // TODO
        /*
         * try { TestListener listener = new TestListener(); String response;
         * int offset=0; connector.reopen();
         *  // HTTP/1.0 offset=0; connector.reopen();
         * response=connector.getResponses("GET /redirect HTTP/1.0\n"+
         * "Connection: Keep-Alive\n"+ "\n" );
         * offset=checkContains(response,offset, "HTTP/1.1 302","302")+1;
         * checkContains(response,offset, "Location: /dump", "redirected");
         * checkContains(response,offset, "Connection: close", "Connection:
         * close");
         *
         *  // HTTP/1.1 offset=0; connector.reopen();
         * response=connector.getResponses("GET /redirect HTTP/1.1\n"+ "Host:
         * localhost\n"+ "\n"+ "GET /redirect HTTP/1.1\n"+ "Host: localhost\n"+
         * "Connection: close\n"+ "\n" ); offset=checkContains(response,offset,
         * "HTTP/1.1 302","302")+1; checkContains(response,offset, "Location:
         * /dump", "redirected"); checkContains(response,offset,
         * "Transfer-Encoding: chunked", "Transfer-Encoding: chunked");
         *
         * offset=checkContains(response,offset, "HTTP/1.1 302","302")+1;
         * checkContains(response,offset, "Location: /dump", "redirected");
         * checkContains(response,offset, "Connection: close", "closed");
         *  // HTTP/1.0 _content offset=0; connector.reopen();
         * response=connector.getResponses("GET /redirect/_content HTTP/1.0\n"+
         * "Connection: Keep-Alive\n"+ "\n"+ "GET /redirect/_content
         * HTTP/1.0\n"+ "\n" ); offset=checkContains(response,offset, "HTTP/1.1
         * 302","302")+1; checkContains(response,offset, "Location: /dump",
         * "redirected"); checkContains(response,offset, "Connection: close",
         * "close no _content length");
         *  // HTTP/1.1 _content offset=0; connector.reopen();
         * response=connector.getResponses("GET /redirect/_content HTTP/1.1\n"+
         * "Host: localhost\n"+ "\n"+ "GET /redirect/_content HTTP/1.1\n"+
         * "Host: localhost\n"+ "Connection: close\n"+ "\n" );
         * offset=checkContains(response,offset, "HTTP/1.1 302","302")+1;
         * checkContains(response,offset, "Location: /dump", "redirected");
         * checkContains(response,offset, "Transfer-Encoding: chunked", "chunked
         * _content length");
         *
         * offset=checkContains(response,offset, "HTTP/1.1 302","302")+1;
         * checkContains(response,offset, "Location: /dump", "redirected");
         * checkContains(response,offset, "Connection: close", "closed");
         *  } catch(Exception e) { e.printStackTrace(); assertTrue(false); }
         *
         */
    }

    /* --------------------------------------------------------------- */
    public void checkContentRange(LocalConnector listener, String tname, String path, String reqRanges, int expectedStatus, String expectedRange,
            String expectedData)
    {
        try
        {
            String response;
            int offset=0;

            String byteRangeHeader="";
            if (reqRanges!=null)
            {
                byteRangeHeader="Range: "+reqRanges+"\n";
            }

            response=connector.getResponses("GET /"+path+" HTTP/1.1\n"+"Host: localhost\n"+byteRangeHeader+"Connection: close\n"+"\n");

            switch (expectedStatus)
            {
                case 200:
                {
                    offset=checkContains(response,offset,"HTTP/1.1 200 OK\r\n",tname+".1. proper 200 OK status code");
                    break;
                }
                case 206:
                {
                    offset=checkContains(response,offset,"HTTP/1.1 206 Partial Content\r\n",tname+".1. proper 206 Partial Content status code");
                    break;
                }
                case 416:
                {
                    offset=checkContains(response,offset,"HTTP/1.1 416 Requested Range Not Satisfiable\r\n",tname
                            +".1. proper 416 Requested Range not Satisfiable status code");
                    break;
                }
            }

            if (expectedRange!=null)
            {
                String expectedContentRange="Content-Range: bytes "+expectedRange+"\r\n";
                offset=checkContains(response,offset,expectedContentRange,tname+".2. _content range "+expectedRange);
            }

            if (expectedStatus==200||expectedStatus==206)
            {
                offset=checkContains(response,offset,expectedData,tname+".3. subrange data: \""+expectedData+"\"");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    public void test14_16()
    {
        // TODO
        /*
         * try { TestListener listener = new TestListener();
         *
         * int id = 0;
         *
         *  // // calibrate with normal request (no ranges); if this doesnt //
         * work, dont expect ranges to work either //
         * connector.checkContentRange( t, Integer.toString(id++),
         * TestRFC2616.testFiles[0].name, null, 200, null,
         * TestRFC2616.testFiles[0].data );
         *
         *  // // server should ignore all range headers which include // at
         * least one syntactically invalid range // String [] totallyBadRanges = {
         * "bytes=a-b", "bytes=-1-2", "bytes=-1-2,2-3", "bytes=-", "bytes=-1-",
         * "bytes=a-b,-1-1-1", "doublehalfwords=1-2", };
         *
         * for (int i = 0; i < totallyBadRanges.length; i++) {
         * connector.checkContentRange( t, "BadRange"+i,
         * TestRFC2616.testFiles[0].name, totallyBadRanges[i], 416, null,
         * TestRFC2616.testFiles[0].data ); }
         *
         *  // // should test for combinations of good and syntactically //
         * invalid ranges here, but I am not certain what the right // behavior
         * is abymore // // a) Range: bytes=a-b,5-8 // // b) Range:
         * bytes=a-b,bytes=5-8 // // c) Range: bytes=a-b // Range: bytes=5-8 //
         *
         *  // // return data for valid ranges while ignoring unsatisfiable //
         * ranges //
         *
         * connector.checkContentRange( t, "bytes=5-8",
         * TestRFC2616.testFiles[0].name, "bytes=5-8", 206, "5-8/26",
         * TestRFC2616.testFiles[0].data.substring(5,8+1) );
         *  // // server should not return a 416 if at least syntactically valid
         * ranges // are is satisfiable // connector.checkContentRange( t,
         * "bytes=5-8,50-60", TestRFC2616.testFiles[0].name, "bytes=5-8,50-60",
         * 206, "5-8/26", TestRFC2616.testFiles[0].data.substring(5,8+1) );
         * connector.checkContentRange( t, "bytes=50-60,5-8",
         * TestRFC2616.testFiles[0].name, "bytes=50-60,5-8", 206, "5-8/26",
         * TestRFC2616.testFiles[0].data.substring(5,8+1) );
         *  // 416 as none are satisfiable connector.checkContentRange( t,
         * "bytes=50-60", TestRFC2616.testFiles[0].name, "bytes=50-60", 416,
         * "*REMOVE_THIS/26", null );
         *
         *  }
         *
         * catch(Exception e) { e.printStackTrace(); assertTrue(false); }
         */
    }

    /* --------------------------------------------------------------- */
    public void test14_23()
    {
        try
        {

            String response;
            int offset=0;

            // HTTP/1.0 OK with no host
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.0\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","200")+1;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 400","400")+1;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host: localhost\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","200")+1;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+"Host:\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200","200")+1;

        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    /* --------------------------------------------------------------- */
    public void test14_35()
    {
        // TODO
        /*
         * try { TestListener listener = new TestListener();
         *  // // test various valid range specs that have not been // tested
         * yet //
         *
         * connector.checkContentRange( t, "bytes=0-2",
         * TestRFC2616.testFiles[0].name, "bytes=0-2", 206, "0-2/26",
         * TestRFC2616.testFiles[0].data.substring(0,2+1) );
         *
         * connector.checkContentRange( t, "bytes=23-",
         * TestRFC2616.testFiles[0].name, "bytes=23-", 206, "23-25/26",
         * TestRFC2616.testFiles[0].data.substring(23,25+1) );
         *
         * connector.checkContentRange( t, "bytes=23-42",
         * TestRFC2616.testFiles[0].name, "bytes=23-42", 206, "23-25/26",
         * TestRFC2616.testFiles[0].data.substring(23,25+1) );
         *
         * connector.checkContentRange( t, "bytes=-3",
         * TestRFC2616.testFiles[0].name, "bytes=-3", 206, "23-25/26",
         * TestRFC2616.testFiles[0].data.substring(23,25+1) );
         *
         * connector.checkContentRange( t, "bytes=23-23,-2",
         * TestRFC2616.testFiles[0].name, "bytes=23-23,-2", 206, "23-23/26",
         * TestRFC2616.testFiles[0].data.substring(23,23+1) );
         *
         * connector.checkContentRange( t, "bytes=-1,-2,-3",
         * TestRFC2616.testFiles[0].name, "bytes=-1,-2,-3", 206, "25-25/26",
         * TestRFC2616.testFiles[0].data.substring(25,25+1) );
         *  }
         *
         * catch(Exception e) { e.printStackTrace(); assertTrue(false); }
         */}

    /* --------------------------------------------------------------- */
    public void test14_39()
    {
        // TODO
        /*
         * try { TestListener listener = new TestListener(); String response;
         * int offset=0; connector.reopen();
         *  // Gzip accepted offset=0; connector.reopen();
         * response=connector.getResponses("GET /R1?gzip HTTP/1.1\n"+ "Host:
         * localhost\n"+ "TE: gzip;q=0.5\n"+ "Connection: close\n"+ "\n");
         * offset=checkContains(response,offset, "HTTP/1.1 200","TE: coding")+1;
         * offset=checkContains(response,offset, "Transfer-Encoding: gzip","TE:
         * coding")+1;
         *  // Gzip not accepted offset=0; connector.reopen();
         * response=connector.getResponses("GET /R1?gzip HTTP/1.1\n"+ "Host:
         * localhost\n"+ "TE: deflate\n"+ "Connection: close\n"+ "\n");
         * offset=checkContains(response,offset, "HTTP/1.1 501","TE: coding not
         * accepted")+1;
         *  } catch(Exception e) { e.printStackTrace(); assertTrue(false); }
         */
    }

    /* --------------------------------------------------------------- */
    public void test19_6()
    {
        try
        {

            String response;
            int offset=0;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.0\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 default close")+10;
            checkNotContained(response,offset,"Connection: close","19.6.2 not assumed");

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.0\n"+"Host: localhost\n"+"Connection: keep-alive\n"+"\n"+

            "GET /R2 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n"+

            "GET /R3 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n");

            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"Connection: keep-alive","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"<html>","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"/R1","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 2")+11;
            offset=checkContains(response,offset,"Connection: close","19.6.2 Keep-alive close")+1;
            offset=checkContains(response,offset,"/R2","19.6.2 Keep-alive close")+3;

            assertEquals("19.6.2 closed",-1,response.indexOf("/R3"));

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.0\n"+"Host: localhost\n"+"Connection: keep-alive\n"+"Content-Length: 10\n"+"\n"+"1234567890\n"+

            "GET /RA HTTP/1.0\n"+"Host: localhost\n"+"Connection: keep-alive\n"+"Content-Length: 10\n"+"\n"+"ABCDEFGHIJ\n"+

            "GET /R2 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n"+

            "GET /R3 HTTP/1.0\n"+"Host: localhost\n"+"Connection: close\n"+"\n");
            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"Connection: keep-alive","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"<html>","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"1234567890","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"Connection: keep-alive","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"<html>","19.6.2 Keep-alive 1")+1;
            offset=checkContains(response,offset,"ABCDEFGHIJ","19.6.2 Keep-alive 1")+1;

            offset=checkContains(response,offset,"HTTP/1.1 200 OK\015\012","19.6.2 Keep-alive 2")+11;
            offset=checkContains(response,offset,"Connection: close","19.6.2 Keep-alive close")+1;
            offset=checkContains(response,offset,"/R2","19.6.2 Keep-alive close")+3;

            assertEquals("19.6.2 closed",-1,response.indexOf("/R3"));

        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    private int checkContains(String s, int offset, String c, String test)
    {
        int o=s.indexOf(c,offset);
        if (o<offset)
        {
            System.err.println("FAILED: "+test);
            System.err.println("'"+c+"' not in:");
            System.err.println(s.substring(offset));
            System.err.flush();
            System.out.println("--\n"+s);
            System.out.flush();
            assertTrue(test,false);
        }
        return o;
    }

    private void checkNotContained(String s, int offset, String c, String test)
    {
        int o=s.indexOf(c,offset);
        assertTrue(test,o<offset);
    }

    private void checkNotContained(String s, String c, String test)
    {
        checkNotContained(s,0,c,test);
    }

}
