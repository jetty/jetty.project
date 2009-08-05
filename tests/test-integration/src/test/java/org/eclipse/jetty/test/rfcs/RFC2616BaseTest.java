// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.test.rfcs;

import static org.junit.Assert.assertThat;
import static org.junit.matchers.JUnitMatchers.containsString;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.TimeZone;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.test.AbstractJettyTestCase;
import org.eclipse.jetty.test.StringAssert;
import org.eclipse.jetty.test.support.StringUtil;
import org.eclipse.jetty.test.support.TestableJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpResponseTester;
import org.eclipse.jetty.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.test.support.rawhttp.HttpTesting;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * <a href="http://tools.ietf.org/html/rfc2616">RFC 2616</a> (HTTP/1.1) Test Case
 */
public abstract class RFC2616BaseTest extends AbstractJettyTestCase
{
    private static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";
    /** STRICT RFC TESTS */
    private static final boolean STRICT = false;
    private List<HttpResponseTester> responses;
    private HttpResponseTester response;
    private HttpTesting http;
    private TestableJettyServer server;

    class TestFile
    {
        String name;
        String modDate;
        String data;
        long length;

        public TestFile(String name)
        {
            this.name = name;
            // HTTP-Date format - see RFC 2616 section 14.29
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            this.modDate = sdf.format(new Date());
        }

        public void setData(String data)
        {
            this.data = data;
            this.length = data.length();
        }
    }

    @Override
    @Before
    public void setUp() throws Exception
    {
        super.setUp();

        File testWorkDir = new File(getTargetDir(),"work" + File.separator + getClass().getSimpleName() + File.separator + getName());
        if (!testWorkDir.exists())
        {
            testWorkDir.mkdirs();
        }

        System.setProperty("java.io.tmpdir",testWorkDir.getAbsolutePath());

        server = getJettyServer();
        server.load();
        server.start();
        http = new HttpTesting(getHttpClientSocket(),server.getServerPort());
    }

    @Override
    @After
    public void tearDown() throws Exception
    {
        server.stop();
        super.tearDown();
    }

    public abstract TestableJettyServer getJettyServer() throws IOException;

    public abstract HttpSocket getHttpClientSocket() throws Exception;

    /**
     * Test Date/Time format Specs.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.3">RFC 2616 (section 3.3)</a>
     */
    @Test
    public void test3_3()
    {
        Calendar expected = Calendar.getInstance();
        expected.set(Calendar.YEAR,1994);
        expected.set(Calendar.MONTH,Calendar.NOVEMBER);
        expected.set(Calendar.DAY_OF_MONTH,6);
        expected.set(Calendar.HOUR_OF_DAY,8);
        expected.set(Calendar.MINUTE,49);
        expected.set(Calendar.SECOND,37);
        expected.set(Calendar.MILLISECOND,0); // Milliseconds is not compared
        expected.set(Calendar.ZONE_OFFSET,0); // Use GMT+0:00
        expected.set(Calendar.DST_OFFSET,0); // No Daylight Savings Offset

        HttpFields fields = new HttpFields();

        // RFC 822 Preferred Format
        fields.put("D1","Sun, 6 Nov 1994 08:49:37 GMT");
        // RFC 822 / RFC 850 Format
        fields.put("D2","Sunday, 6-Nov-94 08:49:37 GMT");
        // RFC 850 / ANSIC C Format
        fields.put("D3","Sun Nov  6 08:49:37 1994");

        // Test parsing
        assertDate("3.3.1 RFC 822 Preferred",expected,fields.getDateField("D1"));
        assertDate("3.3.1 RFC 822 / RFC 850",expected,fields.getDateField("D2"));
        assertDate("3.3.1 RFC 850 / ANSI C",expected,fields.getDateField("D3"));

        // Test formatting
        fields.putDateField("Date",expected.getTime().getTime());
        assertEquals("3.3.1 RFC 822 preferred","Sun, 06 Nov 1994 08:49:37 GMT",fields.getStringField("Date"));
    }

    /**
     * Test Transfer Codings
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.6">RFC 2616 (section 3.6)</a>
     */
    @Test
    public void test3_6() throws Throwable
    {
        // Chunk last
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /tests/R1 HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Transfer-Encoding: chunked,identity\n"); // Invalid Transfer-Encoding
        req1.append("Content-Type: text/plain\n");
        req1.append("Connection: close\n");
        req1.append("\r\n");
        req1.append("5;\r\n");
        req1.append("123\r\n\r\n");
        req1.append("0;\r\n\r\n");

        response = http.request(req1);
        response.assertStatus("3.6 Transfer Coding / Bad 400",HttpStatus.BAD_REQUEST_400);

        // Chunked
        StringBuffer req2 = new StringBuffer();
        req2.append("GET /echo/R1 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Transfer-Encoding: chunked\n");
        req2.append("Content-Type: text/plain\n");
        req2.append("\n");
        req2.append("2;\n"); // 2 chars
        req2.append("12\n");
        req2.append("3;\n"); // 3 chars
        req2.append("345\n");
        req2.append("0;\n\n");

        req2.append("GET /echo/R2 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Transfer-Encoding: chunked\n");
        req2.append("Content-Type: text/plain\n");
        req2.append("\n");
        req2.append("4;\n"); // 4 chars
        req2.append("6789\n");
        req2.append("5;\n"); // 5 chars
        req2.append("abcde\n");
        req2.append("0;\n\n"); // 0 chars

        req2.append("GET /echo/R3 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        responses = http.requests(req2);
        assertEquals("Response Count",3,responses.size());

        response = responses.get(0); // Response 1
        response.assertStatusOK("3.6.1 Transfer Codings / Response 1 Code");
        response.assertBody("3.6.1 Transfer Codings / Chunked String","12345\n");

        response = responses.get(1); // Response 2
        response.assertStatusOK("3.6.1 Transfer Codings / Response 2 Code");
        response.assertBody("3.6.1 Transfer Codings / Chunked String","6789abcde\n");

        response = responses.get(2); // Response 3
        response.assertStatusOK("3.6.1 Transfer Codings / Response 3 Code");
        response.assertNoBody("3.6.1 Transfer Codings / No Body");

        // Chunked

        StringBuffer req3 = new StringBuffer();
        req3.append("POST /echo/R1 HTTP/1.1\n");
        req3.append("Host: localhost\n");
        req3.append("Transfer-Encoding: chunked\n");
        req3.append("Content-Type: text/plain\n");
        req3.append("\n");
        req3.append("3;\n"); // 3 chars
        req3.append("fgh\n");
        req3.append("3;\n"); // 3 chars
        req3.append("Ijk\n");
        req3.append("0;\n\n"); // 0 chars

        req3.append("POST /echo/R2 HTTP/1.1\n");
        req3.append("Host: localhost\n");
        req3.append("Transfer-Encoding: chunked\n");
        req3.append("Content-Type: text/plain\n");
        req3.append("\n");
        req3.append("4;\n"); // 4 chars
        req3.append("lmno\n");
        req3.append("5;\n"); // 5 chars
        req3.append("Pqrst\n");
        req3.append("0;\n\n"); // 0 chars

        req3.append("GET /echo/R3 HTTP/1.1\n");
        req3.append("Host: localhost\n");
        req3.append("Connection: close\n");
        req3.append("\n");

        responses = http.requests(req3);
        assertEquals("Response Count",3,responses.size());

        response = responses.get(0); // Response 1
        response.assertStatusOK("3.6.1 Transfer Codings / Response 1 Code");
        response.assertBody("3.6.1 Transfer Codings / Chunked String","fghIjk\n"); // Complete R1 string

        response = responses.get(1); // Response 2
        response.assertStatusOK("3.6.1 Transfer Codings / Response 2 Code");
        response.assertBody("3.6.1 Transfer Codings / Chunked String","lmnoPqrst\n"); // Complete R2 string

        response = responses.get(2); // Response 3
        response.assertStatusOK("3.6.1 Transfer Codings / Response 3 Code");
        response.assertNoBody("3.6.1 Transfer Codings / No Body");

        // Chunked and keep alive

        StringBuffer req4 = new StringBuffer();
        req4.append("GET /echo/R1 HTTP/1.1\n");
        req4.append("Host: localhost\n");
        req4.append("Transfer-Encoding: chunked\n");
        req4.append("Content-Type: text/plain\n");
        req4.append("Connection: keep-alive\n"); // keep-alive
        req4.append("\n");
        req4.append("3;\n"); // 3 chars
        req4.append("123\n");
        req4.append("3;\n"); // 3 chars
        req4.append("456\n");
        req4.append("0;\n\n"); // 0 chars

        req4.append("GET /echo/R2 HTTP/1.1\n");
        req4.append("Host: localhost\n");
        req4.append("Connection: close\n"); // close
        req4.append("\n");

        responses = http.requests(req4);
        assertEquals("Response Count",2,responses.size());

        response = responses.get(0); // Response 1
        response.assertStatusOK("3.6.1 Transfer Codings / Response 1 Code");
        response.assertBody("3.6.1 Transfer Codings / Chunked String","123456\n"); // Complete R1 string

        response = responses.get(1); // Response 2
        response.assertStatusOK("3.6.1 Transfer Codings / Response 2 Code");
        response.assertNoBody("3.6.1 Transfer Codings / No Body");
    }

    /**
     * Test Quality Values
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.9">RFC 2616 (section 3.9)</a>
     */
    @Test
    public void test3_9()
    {
        HttpFields fields = new HttpFields();

        fields.put("Q","bbb;q=0.5,aaa,ccc;q=0.002,d;q=0,e;q=0.0001,ddd;q=0.001,aa2,abb;q=0.7");
        Enumeration<String> qualities = fields.getValues("Q",", \t");
        List<?> list = HttpFields.qualityList(qualities);
        assertEquals("Quality parameters","aaa",HttpFields.valueParameters(list.get(0).toString(),null));
        assertEquals("Quality parameters","aa2",HttpFields.valueParameters(list.get(1).toString(),null));
        assertEquals("Quality parameters","abb",HttpFields.valueParameters(list.get(2).toString(),null));
        assertEquals("Quality parameters","bbb",HttpFields.valueParameters(list.get(3).toString(),null));
        assertEquals("Quality parameters","ccc",HttpFields.valueParameters(list.get(4).toString(),null));
        assertEquals("Quality parameters","ddd",HttpFields.valueParameters(list.get(5).toString(),null));
    }

    /**
     * Test Message Length
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-4.4">RFC 2616 (section 4.4)</a>
     */
    @Test
    public void test4_4() throws Exception
    {
        // 4.4.2 - transfer length is 'chunked' when the 'Transfer-Encoding' header
        // is provided with a value other than 'identity', unless the
        // request message is terminated with a 'Connection: close'.

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /echo/R1 HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Transfer-Encoding: identity\n");
        req1.append("Content-Type: text/plain\n");
        req1.append("Content-Length: 5\n");
        req1.append("\n");
        req1.append("123\r\n");

        req1.append("GET /echo/R2 HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        responses = http.requests(req1);
        assertEquals("Response Count",2,responses.size());

        response = responses.get(0);
        response.assertStatusOK("4.4.2 Message Length / Response Code");
        response.assertBody("4.4.2 Message Length / Body","123\n");
        response = responses.get(1);
        response.assertStatusOK("4.4.2 Message Length / Response Code");
        response.assertNoBody("4.4.2 Message Length / No Body");

        // 4.4.3 -
        // Client - do not send 'Content-Length' if entity-length
        // and the transfer-length are different.
        // Server - ignore 'Content-Length' if 'Transfer-Encoding' is provided.

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /echo/R1 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Transfer-Encoding: chunked\n");
        req2.append("Content-Type: text/plain\n");
        req2.append("Content-Length: 100\n");
        req2.append("\n");
        req2.append("3;\n");
        req2.append("123\n");
        req2.append("3;\n");
        req2.append("456\n");
        req2.append("0;\n");
        req2.append("\n");

        req2.append("GET /echo/R2 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("Content-Type: text/plain\n");
        req2.append("Content-Length: 6\n");
        req2.append("\n");
        req2.append("7890AB");

        responses = http.requests(req2);
        assertEquals("Response Count",2,responses.size());

        response = responses.get(0); // response 1
        response.assertStatusOK("4.4.3 Ignore Content-Length / Response Code");
        response.assertBody("4.4.3 Ignore Content-Length / Body","123456\n");
        response = responses.get(1); // response 2
        response.assertStatusOK("4.4.3 Ignore Content-Length / Response Code");
        response.assertBody("4.4.3 Ignore Content-Length / Body","7890AB\n");

        // 4.4 - Server can request valid Content-Length from client if client
        // fails to provide a Content-Length.
        // Server can respond with 400 (Bad Request) or 411 (Length Required).

        // NOTE: MSIE breaks this rule
        // TODO: Document which version of MSIE Breaks Rule.
        // TODO: Document which versions of MSIE pass this rule (if any).
        if (STRICT)
        {
            StringBuffer req3 = new StringBuffer();
            req3.append("GET /echo/R2 HTTP/1.1\n");
            req3.append("Host: localhost\n");
            req3.append("Content-Type: text/plain\n");
            req3.append("Connection: close\n");
            req3.append("\n");
            req3.append("123456");

            response = http.request(req3);

            response.assertStatus("4.4 Valid Content-Length Required",HttpStatus.LENGTH_REQUIRED_411);
            response.assertNoBody("4.4 Valid Content-Length Required");

            StringBuffer req4 = new StringBuffer();
            req4.append("GET /echo/R2 HTTP/1.0\n");
            req4.append("Content-Type: text/plain\n");
            req4.append("\n");
            req4.append("123456");

            response = http.request(req4);

            response.assertStatus("4.4 Valid Content-Length Required",HttpStatus.LENGTH_REQUIRED_411);
            response.assertNoBody("4.4 Valid Content-Length Required");
        }
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_DefaultHost() throws Exception
    {
        // Default Host

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /tests/index.html HTTP/1.1\n");
        req1.append("Host: localhost\n"); // default host
        req1.append("Connection: close\n");
        req1.append("\r\n");

        response = http.request(req1);

        response.assertStatusOK("5.2 Default Host");
        response.assertBodyContains("5.2 Default Host","Default DOCRoot");
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_VirtualHost() throws Exception
    {
        // Virtual Host

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /tests/ HTTP/1.1\n");
        req2.append("Host: VirtualHost\n"); // simple virtual host
        req2.append("Connection: close\n");
        req2.append("\r\n");

        response = http.request(req2);

        response.assertStatusOK("5.2 Virtual Host");
        response.assertBodyContains("5.2 Virtual Host","VirtualHost DOCRoot");
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_VirtualHostInsensitive() throws Exception
    {
        // Virtual Host case insensitive

        StringBuffer req3 = new StringBuffer();
        req3.append("GET /tests/ HTTP/1.1\n");
        req3.append("Host: ViRtUalhOst\n"); // mixed case host
        req3.append("Connection: close\n");
        req3.append("\n");

        response = http.request(req3);

        response.assertStatusOK("5.2 Virtual Host (mixed case)");
        response.assertBodyContains("5.2 Virtual Host (mixed case)","VirtualHost DOCRoot");
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_NoVirtualHost() throws Exception
    {
        // No Virtual Host

        StringBuffer req4 = new StringBuffer();
        req4.append("GET /tests/ HTTP/1.1\n");
        req4.append("Connection: close\n");
        req4.append("\n"); // no virtual host

        response = http.request(req4);

        response.assertStatus("5.2 No Host",HttpStatus.BAD_REQUEST_400);
        response.assertNoBody("5.2 No Host");
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_BadVirtualHost() throws Exception
    {
        // Bad Virtual Host

        StringBuffer req5 = new StringBuffer();
        req5.append("GET /tests/ HTTP/1.1\n");
        req5.append("Host: bad.eclipse.org\n"); // Bad virtual host
        req5.append("Connection: close\n");
        req5.append("\n");

        response = http.request(req5);

        response.assertStatusOK("5.2 Bad Host");
        response.assertBodyContains("5.2 Bad Host","Default DOCRoot"); // served by default context
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_VirtualHostAbsoluteURI_Http11_WithoutHostHeader() throws Exception
    {
        // Virtual Host as Absolute URI

        StringBuffer req6 = new StringBuffer();
        req6.append("GET http://VirtualHost/tests/ HTTP/1.1\n");
        req6.append("Connection: close\n");
        req6.append("\n");

        response = http.request(req6);

        // No host header should always return a 400 Bad Request.
        response.assertStatus("5.2 Virtual Host as AbsoluteURI (No Host Header / HTTP 1.1)",HttpStatus.BAD_REQUEST_400);
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_VirtualHostAbsoluteURI_Http10_WithoutHostHeader() throws Exception
    {
        // Virtual Host as Absolute URI

        StringBuffer req6 = new StringBuffer();
        req6.append("GET http://VirtualHost/tests/ HTTP/1.0\n");
        req6.append("Connection: close\n");
        req6.append("\n");

        response = http.request(req6);

        response.assertStatusOK("5.2 Virtual Host as AbsoluteURI (No Host Header / HTTP 1.0)");
        response.assertBodyContains("5.2 Virtual Host as AbsoluteURI (No Host Header / HTTP 1.1)","VirtualHost DOCRoot");
    }

    /**
     * Test The Resource Identified by a Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-5.2">RFC 2616 (section 5.2)</a>
     */
    @Test
    public void test5_2_VirtualHostAbsoluteURI_WithHostHeader() throws Exception
    {
        // Virtual Host as Absolute URI (with Host header)

        StringBuffer req7 = new StringBuffer();
        req7.append("GET http://VirtualHost/tests/ HTTP/1.1\n");
        req7.append("Host: localhost\n"); // is ignored (would normally trigger default context)
        req7.append("Connection: close\n");
        req7.append("\n");

        response = http.request(req7);

        response.assertStatusOK("5.2 Virtual Host as AbsoluteURI (and Host header)");
        response.assertBodyContains("5.2 Virtual Host as AbsoluteURI (and Host header)","VirtualHost DOCRoot");
    }

    /**
     * Test Persistent Connections
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.1">RFC 2616 (section 8.1)</a>
     */
    @Test
    public void test8_1() throws Exception
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /tests/R1.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        response.assertStatusOK("8.1 Persistent Connections");
        response.assertHeaderExists("8.1 Persistent Connections","Content-Length");
        response.assertBodyContains("8.1 Persistent Connections","Resource=R1");

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /tests/R1.txt HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("\n");

        req2.append("GET /tests/R2.txt HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        req2.append("GET /tests/R3.txt HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        responses = http.requests(req2);
        assertEquals("Response Count",2,responses.size()); // Should not have a R3 response.

        response = responses.get(0); // response 1
        response.assertStatusOK("8.1 Persistent Connections");
        response.assertHeaderExists("8.1 Persistent Connections","Content-Length");
        response.assertBodyContains("8.1 Peristent Connections","Resource=R1");

        response = responses.get(1); // response 2
        response.assertStatusOK("8.1.2.2 Persistent Connections / Pipeline");
        response.assertHeaderExists("8.1.2.2 Persistent Connections / Pipeline","Content-Length");
        response.assertHeader("8.1.2.2 Persistent Connections / Pipeline","Connection","close");
        response.assertBodyContains("8.1.2.2 Peristent Connections / Pipeline","Resource=R2");
    }

    /**
     * Test Message Transmission Requirements -- Bad client behaviour, invalid Expect header.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test8_2_ExpectInvalid() throws Exception
    {
        // Expect Failure

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /echo/R1 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Expect: unknown\n"); // Invalid Expect header.
        req2.append("Content-Type: text/plain\n");
        req2.append("Content-Length: 8\n");
        req2.append("\n"); // No body

        response = http.request(req2);

        response.assertStatus("8.2.3 expect failure",HttpStatus.EXPECTATION_FAILED_417);
    }

    /**
     * Test Message Transmission Requirements -- Acceptable bad client behavior, Expect 100 with body content.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test8_2_ExpectWithBody() throws Exception
    {
        // Expect with body

        StringBuffer req3 = new StringBuffer();
        req3.append("GET /echo/R1 HTTP/1.1\n");
        req3.append("Host: localhost\n");
        req3.append("Expect: 100-continue\n"); // Valid Expect header.
        req3.append("Content-Type: text/plain\n");
        req3.append("Content-Length: 8\n");
        req3.append("Connection: close\n");
        req3.append("\n");
        req3.append("123456\r\n"); // Body

        // Should only expect 1 response.
        // The existence of 2 responses usually means a bad "HTTP/1.1 100" was received.
        response = http.request(req3);

        response.assertStatusOK("8.2.3 expect 100");
    }

    /**
     * Test Message Transmission Requirements
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test8_2_ExpectNormal() throws Exception
    {
        // Expect 100

        StringBuffer req4 = new StringBuffer();
        req4.append("GET /echo/R1 HTTP/1.1\n");
        req4.append("Host: localhost\n");
        req4.append("Connection: close\n");
        req4.append("Expect: 100-continue\n"); // Valid Expect header.
        req4.append("Content-Type: text/plain\n");
        req4.append("Content-Length: 7\n");
        req4.append("\n"); // No body

        Socket sock = http.open();
        try
        {
            http.send(sock,req4);

            http.setTimeoutMillis(2000);
            response = http.readAvailable(sock);
            response.assertStatus("8.2.3 expect 100",HttpStatus.CONTINUE_100);

            http.send(sock,"654321\n"); // Now send the data
            response = http.read(sock);

            response.assertStatusOK("8.2.3 expect 100");
            response.assertBody("8.2.3 expect 100","654321\n");
        }
        finally
        {
            http.close(sock);
        }
    }

    /**
     * Test OPTIONS (HTTP) method - Server Options
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-9.2">RFC 2616 (section 9.2)</a>
     */
    @Test
    public void test9_2_ServerOptions() throws Exception
    {
        // Unsupported in Jetty.
        // Server can handle many webapps, each with their own set of supported OPTIONS.
        // Both www.cnn.com and www.apache.org do NOT support this request as well.

        if (STRICT)
        {
            // Server OPTIONS

            StringBuffer req1 = new StringBuffer();
            req1.append("OPTIONS * HTTP/1.1\n"); // Apply to server in general, rather than a specific resource
            req1.append("Connection: close\n");
            req1.append("Host: localhost\n");
            req1.append("\n");

            response = http.request(req1);

            response.assertStatusOK("9.2 OPTIONS");
            response.assertHeaderExists("9.2 OPTIONS","Allow");
            // Header expected ...
            // Allow: GET, HEAD, POST, PUT, DELETE, MOVE, OPTIONS, TRACE
            String allow = response.getHeader("Allow");
            String expectedMethods[] =
            { "GET", "HEAD", "POST", "PUT", "DELETE", "MOVE", "OPTIONS", "TRACE" };
            for (String expectedMethod : expectedMethods)
            {
                assertThat(allow,containsString(expectedMethod));
            }
            response.assertHeader("9.2 OPTIONS","Content-Length","0"); // Required if no response body.
        }
    }

    /**
     * Test OPTIONS (HTTP) method - Resource Options
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-9.2">RFC 2616 (section 9.2)</a>
     */
    @Test
    public void test9_2_ResourceOptions() throws Exception
    {
        // Jetty is conditionally compliant.
        // Possible Bug in the Spec.
        // The content-length: 0 in the spec is not appropriate if the connection is being closed.

        // Resource specific OPTIONS
        StringBuffer req2 = new StringBuffer();
        req2.append("OPTIONS /rfc2616-webapp/httpmethods HTTP/1.1\n"); // Apply to specific resource
        req2.append("Host: localhost\n");
        req2.append("\n");

        // Test issues 2 requests. first as OPTIONS (not closed),
        // second as GET (closed), this is to allow the 2 conflicting aspects of the
        // RFC2616 rules with regards to section 9.2 (OPTIONS) and section 4.4 (Message Length)
        // to not conflict with each other.

        req2.append("GET /rfc2616-webapp/httpmethods HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n"); // Close this second request
        req2.append("\n");

        responses = http.requests(req2);

        assertEquals("Response Count",2,responses.size()); // Should have 2 responses

        response = responses.get(0); // Only interested in first response
        response.assertHeaderExists("9.2 OPTIONS","Allow");
        // Header expected ...
        // Allow: GET, HEAD, POST, TRACE, OPTIONS
        String allow = response.getHeader("Allow");
        String expectedMethods[] =
        { "GET", "HEAD", "POST", "OPTIONS", "TRACE" };
        for (String expectedMethod : expectedMethods)
        {
            assertThat(allow,containsString(expectedMethod));
        }

        response.assertHeader("9.2 OPTIONS","Content-Length","0"); // Required if no response body.
    }

    /**
     * Test HEAD (HTTP) method
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-9.4">RFC 2616 (section 9.4)</a>
     */
    @Test
    public void test9_4() throws Exception
    {
        /* Test GET first. (should have body) */

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /tests/R1.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        response.assertStatusOK("9.4 GET / Response Code");
        response.assertHeader("9.4 GET / Content Type","Content-Type","text/plain");
        response.assertHeader("9.4 HEAD / Content Type","Content-Length","25");
        response.assertBody("9.4 GET / Body","Host=Default\nResource=R1\n");

        /* Test HEAD next. (should have no body) */

        StringBuffer req2 = new StringBuffer();
        req2.append("HEAD /tests/R1.txt HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        // Need to get the HEAD response in a RAW format, as HttpParser.parse()
        // can't properly parse a HEAD response.
        Socket sock = http.open();
        try
        {
            http.send(sock,req2);

            String rawHeadResponse = http.readRaw(sock);
            int headResponseLength = rawHeadResponse.length();
            // Only interested in the response header from the GET request above.
            String rawGetResponse = response.getRawResponse().toString().substring(0,headResponseLength);

            // As there is a possibility that the time between GET and HEAD requests
            // can cross the second mark. (eg: GET at 11:00:00.999 and HEAD at 11:00:01.001)
            // So with that knowledge, we will remove the 'Date:' header from both sides before comparing.
            List<String> linesGet = StringUtil.asLines(rawGetResponse);
            List<String> linesHead = StringUtil.asLines(rawHeadResponse);

            StringUtil.removeStartsWith("Date: ",linesGet);
            StringUtil.removeStartsWith("Date: ",linesHead);

            // Compare the 2 lists of lines to make sure they contain the same information
            // Do not worry about order of the headers, as that's not important to test,
            // just the existence of the same headers
            StringAssert.assertContainsSame("9.4 HEAD equals GET",linesGet,linesHead);
        }
        finally
        {
            http.close(sock);
        }
    }

    /**
     * Test TRACE (HTTP) method
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-9.8">RFC 2616 (section 9.8)</a>
     */
    @Test
    public void test9_8() throws Exception
    {

        StringBuffer req1 = new StringBuffer();
        req1.append("TRACE /rfc2616-webapp/httpmethods HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        HttpResponseTester response = http.request(req1);

        response.assertStatusOK("9.8 TRACE / Response Code");
        response.assertHeader("9.8 TRACE / Content Type","Content-Type","message/http");
        response.assertBodyContains("9.8 TRACE / echo","TRACE /rfc2616-webapp/httpmethods HTTP/1.1");
        response.assertBodyContains("9.8 TRACE / echo","Host: localhost");
    }

    /**
     * Test 206 Partial Content (Response Code)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.2.7">RFC 2616 (section 10.2.7)</a>
     */
    @Test
    public void test10_2_7() throws Exception
    {
        // check to see if corresponding GET w/o range would return
        // a) ETag
        // b) Content-Location
        // these same headers will be required for corresponding
        // sub range requests

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        boolean noRangeHasContentLocation = response.hasHeader("Content-Location");
        boolean noRangeHasETag = response.hasHeader("ETag");

        // now try again for the same resource but this time WITH range header

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("Range: bytes=1-3\n"); // request first 3 bytes
        req2.append("\n");

        http.enableDebug();
        response = http.request(req2);

        response.assertStatus("10.2.7 Partial Content",HttpStatus.PARTIAL_CONTENT_206);

        // (point 1) A 206 response MUST contain either a Content-Range header
        // field (section 14.16) indicating the range included with this
        // response, or a multipart/byteranges Content-Type including Content-Range
        // fields for each part. If a Content-Length header field is present
        // in the response, its value MUST match the actual number of OCTETs
        // transmitted in the message-body.

        if (response.hasHeader("Content-Range"))
        {
            response.assertHeader("10.2.7 Partial Content / Response / Content Range","Content-Range","bytes 1-3/27");
        }

        if (response.hasHeader("Content-Length"))
        {
            response.assertHeader("10.2.7 Patial Content / Response / Content Length","Content-Length","3");
        }

        // (point 2) A 206 response MUST contain a Date header
        response.assertHeaderExists("10.2.7 Partial Content / Response / Date","Date");

        // (point 3) A 206 response MUST contain ETag and/or Content-Location,
        // if the header would have been sent in a 200 response to the same request
        if (noRangeHasContentLocation)
        {
            response.assertHeaderExists("10.2.7 Partial Content / Content-Location","Content-Location");
        }
        if (noRangeHasETag)
        {
            response.assertHeaderExists("10.2.7 Partial Content / Content-Location","ETag");
        }

        // (point 4) A 206 response MUST contain Expires, Cache-Control, and/or Vary,
        // if the field-value might differ from that sent in any previous response
        // for the same variant

        // TODO: Not sure how to test this condition.

        // Test the body sent
        response.assertBody("10.2.7 Partial Content","BCD"); // should only have bytes 1-3
    }

    /**
     * Test Redirection 3xx (Response Code)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test10_3_RedirectHttp10Path() throws Exception
    {
        String specId;

        String serverURI = server.getServerURI().toASCIIString();

        // HTTP/1.0

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /redirect/ HTTP/1.0\n");
        req1.append("Connection: Close\n");
        req1.append("\n");

        response = http.request(req1);

        specId = "10.3 Redirection HTTP/1.0 - basic";
        response.assertStatus(specId,HttpStatus.FOUND_302);
        response.assertHeader(specId,"Location",serverURI + "/tests/");
        response.assertHeader(specId,"Connection","close");

    }

    /**
     * Test Redirection 3xx (Response Code)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test10_3_RedirectHttp11Path() throws Exception
    {
        // HTTP/1.1

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /redirect/ HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("\n");

        req2.append("GET /redirect/ HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        responses = http.requests(req2);
        assertEquals("Response Count",2,responses.size());

        response = responses.get(0);
        String specId = "10.3 Redirection HTTP/1.1 - basic (response 1)";
        response.assertStatus(specId,HttpStatus.FOUND_302);
        response.assertHeader(specId,"Location",server.getScheme() + "://localhost/tests/");

        response = responses.get(1);
        specId = "10.3 Redirection HTTP/1.1 - basic (response 2)";
        response.assertStatus(specId,HttpStatus.FOUND_302);
        response.assertHeader(specId,"Location",server.getScheme() + "://localhost/tests/");
        response.assertHeader(specId,"Connection","close");

    }

    /**
     * Test Redirection 3xx (Response Code)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test10_3_RedirectHttp10Resource() throws Exception
    {
        // HTTP/1.0 - redirect with resource/content

        StringBuffer req3 = new StringBuffer();
        req3.append("GET /redirect/R1.txt HTTP/1.0\n");
        req3.append("Host: localhost\n");
        req3.append("Connection: close\n");
        req3.append("\n");

        response = http.request(req3);

        String specId = "10.3 Redirection HTTP/1.0 w/content";
        response.assertStatus(specId,HttpStatus.FOUND_302);
        response.assertHeader(specId,"Location",server.getScheme() + "://localhost/tests/R1.txt");
        response.assertHeader(specId,"Connection","close");
        response.assertHeaderNotPresent(specId,"Content-Length");

    }

    /**
     * Test Redirection 3xx (Response Code)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test10_3_RedirectHttp11Resource() throws Exception
    {
        // HTTP/1.1 - redirect with resource/content

        StringBuffer req4 = new StringBuffer();
        req4.append("GET /redirect/R2.txt HTTP/1.1\n");
        req4.append("Host: localhost\n");
        req4.append("Connection: close\n");
        req4.append("\n");

        response = http.request(req4);

        String specId = "10.3 Redirection HTTP/1.1 w/content";
        response.assertStatus(specId,HttpStatus.FOUND_302);
        response.assertHeader(specId,"Location",server.getScheme() + "://localhost/tests/R2.txt");
        response.assertHeader(specId,"Connection","close");
        response.assertHeaderNotPresent(specId,"Content-Length");
    }

    /**
     * Test Accept-Encoding (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.3">RFC 2616 (section 14.3)</a>
     */
    @Test
    public void test14_3_AcceptEncodingGzip() throws Exception
    {
        String specId;

        // Gzip accepted

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/solutions.html HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Accept-Encoding: gzip\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);
        specId = "14.3 Accept-Encoding Header";
        response.assertStatusOK(specId);
        response.assertHeader(specId,"Content-Encoding","gzip");
        response.assertHeader(specId,"Content-Type","text/html");
    }

    /**
     * Test Content-Range (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_NoRange() throws Exception
    {
        //
        // calibrate with normal request (no ranges); if this doesnt
        // work, dont expect ranges to work either
        //

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        response.assertStatusOK();
        response.assertBody(ALPHA);
    }

    private void assertBadContentRange(String rangedef) throws IOException
    {
        //
        // server should ignore all range headers which include
        // at least one syntactically invalid range
        //

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Content-Range: ").append(rangedef).append("\n"); // Invalid range
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        response.assertStatus("BadRange: '" + rangedef + "'",HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE_416);
    }

    /**
     * Test Content-Range (Header Field) - Bad Range Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_BadContentRange_InvalidSyntax() throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        assertBadContentRange("bytes=a-b"); // Invalid due to non-digit entries
        assertBadContentRange("bytes=-"); // Invalid due to missing range ends
        assertBadContentRange("bytes=-1-"); // Invalid due negative to end range
        assertBadContentRange("doublehalfwords=1-2"); // Invalid due to bad key 'doublehalfwords'
    }

    /**
     * Test Content-Range (Header Field) - Bad Range Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_BadContentRange_NegativeUse() throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        assertBadContentRange("bytes=-1"); // Invalid due negative range use
        assertBadContentRange("bytes=-1-"); // Invalid due negative to end range
        assertBadContentRange("bytes=-1-2"); // Invalid due to use of negative range start (not allowed)
    }

    /**
     * Test Content-Range (Header Field) - Bad Range Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_BadContentRange_MultipleRanges() throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        assertBadContentRange("bytes=1-2,4-5"); // Invalid due to multiple entries
        assertBadContentRange("bytes=a-b,-1-1-1"); // Invalid due to multiple entries, non-digits, and invalid range syntax
        assertBadContentRange("bytes=-1-2,2-3"); // Invalid because of 2 entries, and use of negative range end
    }

    /**
     * Test Content-Range (Header Field) - Bad Range Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_BadContentRange_OutOfRange() throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        // A syntactically valid range, but is a range outside of the
        // size of the data is also considered bad.
        assertBadContentRange("bytes=50,60");
    }

    private void assertPartialContentRange(String rangedef, String expectedRange, String expectedBody) throws IOException
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Content-Range: ").append(rangedef).append("\n"); // Invalid range
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        String msg = "Partial Range: '" + rangedef + "'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);
        response.assertHeader(msg,"Content-Range","bytes " + expectedRange);
        response.assertBody(msg,expectedBody);
    }

    /**
     * Test Content-Range (Header Field) - Tests multiple ranges, where all defined ranges are syntactically valid, however some ranges are outside of the
     * limits of the available data
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_PartialRange() throws Exception
    {
        String alpha = ALPHA;

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable

        assertPartialContentRange("bytes=5-8,50-60","5-8/27",alpha.substring(5,8 + 1));
        assertPartialContentRange("bytes=50-60,5-8","5-8/27",alpha.substring(5,8 + 1));
    }

    /**
     * Test Content-Range (Header Field) - Tests single Range request header with 2 ranges defined, where there is a mixed case of validity, 1 range invalid,
     * another 1 valid.
     * 
     * Only the valid range should be processed. The invalid range should be ignored.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_PartialRange_MixedRanges() throws Exception
    {
        String alpha = ALPHA;

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable
        //
        // should test for combinations of good and syntactically
        // invalid ranges here, but I am not certain what the right
        // behavior is anymore
        //
        // return data for valid ranges while ignoring unsatisfiable
        // ranges

        // a) Range: bytes=a-b,5-8

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: bytes=a-b,5-8\n"); // Invalid range, then Valid range
        req1.append("Connection: close\n");
        req1.append("\n");

        http.setTimeoutMillis(60000);
        http.enableDebug();
        response = http.request(req1);

        String msg = "Partial Range (Mixed): 'bytes=a-b,5-8'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);
        response.assertHeader(msg,"Content-Range","bytes 5-8/27");
        response.assertBody(msg,alpha.substring(5,8 + 1));
    }

    /**
     * Test Content-Range (Header Field) - Tests single Range request header with 2 ranges defined, where there is a mixed case of validity, 1 range invalid,
     * another 1 valid.
     * 
     * Only the valid range should be processed. The invalid range should be ignored.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_PartialRange_MixedBytes() throws Exception
    {
        String alpha = ALPHA;

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable
        //
        // should test for combinations of good and syntactically
        // invalid ranges here, but I am not certain what the right
        // behavior is anymore
        //
        // return data for valid ranges while ignoring unsatisfiable
        // ranges

        // b) Range: bytes=a-b,bytes=5-8

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: bytes=a-b,bytes=5-8\n"); // Invalid range, then Valid range
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        String msg = "Partial Range (Mixed): 'bytes=a-b,bytes=5-8'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);
        response.assertHeader(msg,"Content-Range","bytes 5-8/27");
        response.assertBody(msg,alpha.substring(5,8 + 1));
    }

    /**
     * Test Content-Range (Header Field) - Tests multiple Range request headers, where there is a mixed case of validity, 1 range invalid, another 1 valid.
     * 
     * Only the valid range should be processed. The invalid range should be ignored.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test14_16_PartialRange_MixedMultiple() throws Exception
    {
        String alpha = ALPHA;

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable
        //
        // should test for combinations of good and syntactically
        // invalid ranges here, but I am not certain what the right
        // behavior is anymore
        //
        // return data for valid ranges while ignoring unsatisfiable
        // ranges

        // c) Range: bytes=a-b
        // Range: bytes=5-8

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: bytes=a-b\n"); // Invalid range
        req1.append("Range: bytes=5-8\n"); // Valid range
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        String msg = "Partial Range (Mixed): 'bytes=a-b' 'bytes=5-8'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);
        response.assertHeader(msg,"Content-Range","bytes 5-8/27");
        response.assertBody(msg,alpha.substring(5,8 + 1));
    }

    /**
     * Test Host (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.23">RFC 2616 (section 14.23)</a>
     */
    @Test
    public void test14_23_Http10_NoHostHeader() throws Exception
    {
        HttpResponseTester response;

        // HTTP/1.0 OK with no host

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /tests/R1.txt HTTP/1.0\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);
        response.assertStatusOK("14.23 HTTP/1.0 - No Host");
    }

    /**
     * Test Host (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.23">RFC 2616 (section 14.23)</a>
     */
    @Test
    public void test14_23_Http11_NoHost() throws Exception
    {
        // HTTP/1.1 400 (bad request) with no host

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /tests/R1.txt HTTP/1.1\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        response = http.request(req2);
        response.assertStatus("14.23 HTTP/1.1 - No Host",HttpStatus.BAD_REQUEST_400);
    }

    /**
     * Test Host (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.23">RFC 2616 (section 14.23)</a>
     */
    @Test
    public void test14_23_ValidHost() throws Exception
    {
        // HTTP/1.1 - Valid host

        StringBuffer req3 = new StringBuffer();
        req3.append("GET /tests/R1.txt HTTP/1.1\n");
        req3.append("Host: localhost\n");
        req3.append("Connection: close\n");
        req3.append("\n");

        response = http.request(req3);
        response.assertStatusOK("14.23 HTTP/1.1 - Valid Host");
    }

    /**
     * Test Host (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.23">RFC 2616 (section 14.23)</a>
     */
    @Test
    public void test14_23_IncompleteHostHeader() throws Exception
    {
        // HTTP/1.1 - Incomplete (empty) Host header

        StringBuffer req4 = new StringBuffer();
        req4.append("GET /tests/R1.txt HTTP/1.1\n");
        req4.append("Host:\n");
        req4.append("Connection: close\n");
        req4.append("\n");

        response = http.request(req4);
        response.assertStatusOK("14.23 HTTP/1.1 - Empty Host");
    }

    /**
     * Tests the (byte) "Range" header for partial content.
     * 
     * Note: This is similar to {@link #assertPartialContentRange(String, String, String)} but uses the "Range" header and not the "Content-Range" header.
     * 
     * @param rangedef
     * @param expectedRange
     * @param expectedBody
     * @throws IOException
     */
    private void assertByteRange(String rangedef, String expectedRange, String expectedBody) throws IOException
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: ").append(rangedef).append("\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        http.enableDebug();
        response = http.request(req1);

        String msg = "Partial (Byte) Range: '" + rangedef + "'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);
        // It might be strange to see a "Content-Range' response header to a 'Range' request,
        // but this is appropriate per the RFC2616 spec.
        response.assertHeader(msg,"Content-Range","bytes " + expectedRange);
        response.assertBody(msg,expectedBody);
    }

    /**
     * Test Range (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test14_35_Range() throws Exception
    {
        //
        // test various valid range specs that have not been
        // tested yet
        //

        String alpha = ALPHA;

        // First 3 bytes
        assertByteRange("bytes=0-2","0-2/27",alpha.substring(0,2 + 1));

        // From byte offset 23 thru the end of the content
        assertByteRange("bytes=23-","23-26/27",alpha.substring(23));

        // Request byte offset 23 thru 42 (only 26 bytes in content)
        // The last 3 bytes are returned.
        assertByteRange("bytes=23-42","23-26/27",alpha.substring(23,26 + 1));

        // Request the last 3 bytes
        assertByteRange("bytes=-3","24-26/27",alpha.substring(24,26 + 1));
    }

    /**
     * Test Range (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test14_35_Range_Multipart1() throws Exception
    {
        String rangedef = "23-23,-2"; // Request byte at offset 23, and the last 2 bytes

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: ").append(rangedef).append("\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        String msg = "Partial (Byte) Range: '" + rangedef + "'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);

        String contentType = response.getHeader("Content-Type");
        // RFC states that multiple parts should result in multipart/byteranges Content type.
        StringAssert.assertContains(msg + " Content-Type",contentType,"multipart/byteranges");

        // Collect 'boundary' string
        String boundary = null;
        String parts[] = StringUtil.split(contentType,';');
        for (int i = 0; i < parts.length; i++)
        {
            if (parts[i].trim().startsWith("boundary="))
            {
                String boundparts[] = StringUtil.split(parts[i],'=');
                assertEquals(msg + " Boundary parts.length",2,boundparts.length);
                boundary = boundparts[1];
            }
        }

        assertNotNull(msg + " Should have found boundary in Content-Type header",boundary);

        // Find boundary offsets within body
        List<HttpResponseTester> multiparts = response.findBodyMultiparts(boundary);
        assertEquals(msg + " multiparts in body (count)",2,multiparts.size());

        // Validate multipart #1
        HttpResponseTester multipart1 = multiparts.get(0);
        multipart1.assertHeader(msg + " Multipart 1","Content-Type","text/plain");
        multipart1.assertHeader(msg + " Multipart 1","Content-Range","bytes 23-23/27");
        multipart1.assertBody(msg + " Multipart 1","X");

        // Validate multipart #2
        HttpResponseTester multipart2 = multiparts.get(1);
        multipart2.assertHeader(msg + " Multipart 2","Content-Type","text/plain");
        multipart2.assertHeader(msg + " Multipart 2","Content-Range","bytes 25-26/27");
        multipart2.assertBody(msg + " Multipart 2","Z\n");
    }

    /**
     * Test Range (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test14_35_Range_Multipart2() throws Exception
    {
        // Request the last 1 byte, last 2 bytes, and last 3 bytes.
        // This is an example of overlapping ranges

        String rangedef = "-1,-2,-3";

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: ").append(rangedef).append("\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        http.enableDebug();
        response = http.request(req1);

        String msg = "Partial (Byte) Range: '" + rangedef + "'";
        response.assertStatus(msg,HttpStatus.PARTIAL_CONTENT_206);

        String contentType = response.getHeader("Content-Type");
        // RFC states that multiple parts should result in multipart/byteranges Content type.
        StringAssert.assertContains(msg + " Content-Type",contentType,"multipart/byteranges");

        // Collect 'boundary' string
        String boundary = null;
        String parts[] = StringUtil.split(contentType,';');
        for (int i = 0; i < parts.length; i++)
        {
            if (parts[i].trim().startsWith("boundary="))
            {
                String boundparts[] = StringUtil.split(parts[i],'=');
                assertEquals(msg + " Boundary parts.length",2,boundparts.length);
                boundary = boundparts[1];
            }
        }

        assertNotNull(msg + " Should have found boundary in Content-Type header",boundary);

        // Find boundary offsets within body
        List<HttpResponseTester> multiparts = response.findBodyMultiparts(boundary);
        assertEquals(msg + " multiparts in body (count)",3,multiparts.size());

        // Validate multipart #1
        HttpResponseTester multipart1 = multiparts.get(0);
        multipart1.assertHeader(msg + " Multipart 1","Content-Type","text/plain");
        multipart1.assertHeader(msg + " Multipart 1","Content-Range","bytes 26-26/27");
        multipart1.assertBody(msg + " Multipart 1","\n");

        // Validate multipart #2
        HttpResponseTester multipart2 = multiparts.get(1);
        multipart2.assertHeader(msg + " Multipart 2","Content-Type","text/plain");
        multipart2.assertHeader(msg + " Multipart 2","Content-Range","bytes 25-26/27");
        multipart2.assertBody(msg + " Multipart 2","Z\n");

        // Validate multipart #3
        HttpResponseTester multipart3 = multiparts.get(2);
        multipart3.assertHeader(msg + " Multipart 3","Content-Type","text/plain");
        multipart3.assertHeader(msg + " Multipart 3","Content-Range","bytes 24-26/27");
        multipart3.assertBody(msg + " Multipart 3","YZ\n");
    }

    /**
     * Test Range (Header Field)
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test14_35_PartialRange() throws Exception
    {
        //
        // test various valid range specs that have not been
        // tested yet
        //

        String alpha = ALPHA;

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable

        assertByteRange("bytes=5-8,50-60","5-8/27",alpha.substring(5,8 + 1));
        assertByteRange("bytes=50-60,5-8","5-8/27",alpha.substring(5,8 + 1));
    }

    private void assertBadByteRange(String rangedef) throws IOException
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /rfc2616-webapp/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Range: ").append(rangedef).append("\n"); // Invalid range
        req1.append("Connection: close\n");
        req1.append("\n");

        response = http.request(req1);

        response.assertStatus("BadByteRange: '" + rangedef + "'",HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE_416);
    }

    /**
     * Test Range (Header Field) - Bad Range Request
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test14_35_BadRange_InvalidSyntax() throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        assertBadByteRange("bytes=a-b"); // Invalid due to non-digit entries
        assertBadByteRange("bytes=-"); // Invalid due to missing range ends
        assertBadByteRange("bytes=-1-"); // Invalid due negative to end range
        assertBadByteRange("doublehalfwords=1-2"); // Invalid due to bad key 'doublehalfwords'
    }

    /**
     * Test TE (Header Field) / Transfer Codings
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.39">RFC 2616 (section 14.39)</a>
     */
    @Test
    public void test14_39_TEGzip() throws Exception
    {
        if (STRICT)
        {
            String specId;

            // Gzip accepted

            StringBuffer req1 = new StringBuffer();
            req1.append("GET /rfc2616-webapp/solutions.html HTTP/1.1\n");
            req1.append("Host: localhost\n");
            req1.append("TE: gzip\n");
            req1.append("Connection: close\n");
            req1.append("\n");

            http.enableDebug();
            response = http.request(req1);
            specId = "14.39 TE Header";
            response.assertStatusOK(specId);
            response.assertHeader(specId,"Transfer-Encoding","gzip");
        }
    }

    /**
     * Test TE (Header Field) / Transfer Codings
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.39">RFC 2616 (section 14.39)</a>
     */
    @Test
    public void test14_39_TEDeflate() throws Exception
    {
        if (STRICT)
        {
            String specId;

            // Deflate not accepted
            StringBuffer req2 = new StringBuffer();
            req2.append("GET /rfc2616-webapp/solutions.html HTTP/1.1\n");
            req2.append("Host: localhost\n");
            req2.append("TE: deflate\n"); // deflate not accepted
            req2.append("Connection: close\n");
            req2.append("\n");

            response = http.request(req2);
            specId = "14.39 TE Header";
            response.assertStatus(specId,HttpStatus.NOT_IMPLEMENTED_501); // Error on TE (deflate not supported)
        }
    }

    /**
     * Test Compatibility with Previous (HTTP) Versions.
     * 
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-19.6">RFC 2616 (section 19.6)</a>
     */
    @Test
    public void test19_6() throws Exception
    {
        List<HttpResponseTester> responses;
        HttpResponseTester response;
        String specId;

        /* Compatibility with HTTP/1.0 */

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /tests/R1.txt HTTP/1.0\n");
        req1.append("\n");

        response = http.request(req1);
        specId = "19.6 Compatibility with HTTP/1.0 - simple request";
        response.assertStatusOK(specId);
        response.assertHeaderNotPresent(specId + " - connection closed not assumed","Connection");

        /* Compatibility with HTTP/1.0 */

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /tests/R1.txt HTTP/1.0\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: keep-alive\n");
        req2.append("\n");

        req2.append("GET /tests/R2.txt HTTP/1.0\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n"); // Connection closed here
        req2.append("\n");

        req2.append("GET /tests/R3.txt HTTP/1.0\n"); // This request should not
        // be handled
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        responses = http.requests(req2);
        // Since R2 closes the connection, should only get 2 responses (R1 &
        // R2), not (R3)
        assertEquals("Response Count",2,responses.size());

        response = responses.get(0); // response 1
        specId = "19.6.2 Compatibility with previous HTTP - Keep-alive";
        response.assertStatusOK(specId);
        response.assertHeader(specId,"Connection","keep-alive");
        response.assertBodyContains(specId,"Resource=R1");

        response = responses.get(1); // response 2
        response.assertStatusOK(specId);
        response.assertHeader(specId,"Connection","close");
        response.assertBodyContains(specId,"Resource=R2");

        /* Compatibility with HTTP/1.0 */

        StringBuffer req3 = new StringBuffer();
        req3.append("GET /echo/R1 HTTP/1.0\n");
        req3.append("Host: localhost\n");
        req3.append("Connection: keep-alive\n");
        req3.append("Content-Length: 10\n");
        req3.append("\n");
        req3.append("1234567890\n");

        req3.append("GET /echo/RA HTTP/1.0\n");
        req3.append("Host: localhost\n");
        req3.append("Connection: keep-alive\n");
        req3.append("Content-Length: 10\n");
        req3.append("\n");
        req3.append("ABCDEFGHIJ\n");

        req3.append("GET /tests/R2.txt HTTP/1.0\n");
        req3.append("Host: localhost\n");
        req3.append("Connection: close\n"); // Close connection here
        req3.append("\n");

        req3.append("GET /tests/R3.txt HTTP/1.0\n"); // This request should not
        // be handled.
        req3.append("Host: localhost\n");
        req3.append("Connection: close\n");
        req3.append("\n");
        responses = http.requests(req3);
        assertEquals("Response Count",3,responses.size());

        specId = "19.6.2 Compatibility with HTTP/1.0- Keep-alive";
        response = responses.get(0);
        response.assertStatusOK(specId);
        response.assertHeader(specId,"Connection","keep-alive");
        response.assertBody(specId,"1234567890\n");

        response = responses.get(1);
        response.assertStatusOK(specId);
        response.assertHeader(specId,"Connection","keep-alive");
        response.assertBody(specId,"ABCDEFGHIJ\n");

        response = responses.get(2);
        response.assertStatusOK(specId);
        response.assertHeader(specId,"Connection","close");
        response.assertBody(specId,"Host=Default\nResource=R2\n");
    }

    protected void assertDate(String msg, Calendar expectedTime, long actualTime)
    {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, d MMMM yyyy HH:mm:ss:SSS zzz");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String actual = sdf.format(new Date(actualTime));
        String expected = sdf.format(expectedTime.getTime());

        assertEquals(msg,expected,actual);
    }
}
