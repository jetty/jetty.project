//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErrorHandlerTest
{
    StacklessLogging stacklessLogging;
    Server server;
    LocalConnector connector;

    @BeforeEach
    public void before() throws Exception
    {
        stacklessLogging = new StacklessLogging(HttpChannel.class);
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        ContextHandler context = new ContextHandler(server);
        context.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (baseRequest.getDispatcherType() == DispatcherType.ERROR)
                {
                    baseRequest.setHandled(true);
                    response.sendError((Integer)request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
                    return;
                }

                if (target.startsWith("/charencoding/"))
                {
                    baseRequest.setHandled(true);
                    response.setCharacterEncoding("utf-8");
                    response.sendError(404);
                    return;
                }

                if (target.startsWith("/badmessage/"))
                {
                    int code = Integer.parseInt(target.substring(target.lastIndexOf('/') + 1));
                    throw new ServletException(new BadMessageException(code));
                }

                // produce an exception with an JSON formatted cause message
                if (target.startsWith("/jsonmessage/"))
                {
                    String message = "\"}, \"glossary\": {\n \"title\": \"example\"\n }\n {\"";
                    throw new ServletException(new RuntimeException(message));
                }

                // produce an exception with an XML cause message
                if (target.startsWith("/xmlmessage/"))
                {
                    String message =
                        "<!DOCTYPE glossary PUBLIC \"-//OASIS//DTD DocBook V3.1//EN\">\n" +
                            " <glossary>\n" +
                            "  <title>example glossary</title>\n" +
                            " </glossary>";
                    throw new ServletException(new RuntimeException(message));
                }

                // produce an exception with an HTML cause message
                if (target.startsWith("/htmlmessage/"))
                {
                    String message = "<hr/><script>alert(42)</script>%3Cscript%3E";
                    throw new ServletException(new RuntimeException(message));
                }

                // produce an exception with a UTF-8 cause message
                if (target.startsWith("/utf8message/"))
                {
                    // @checkstyle-disable-check : AvoidEscapedUnicodeCharacters
                    String message = "Euro is &euro; and \u20AC and %E2%82%AC";
                    // @checkstyle-enable-check : AvoidEscapedUnicodeCharacters
                    throw new ServletException(new RuntimeException(message));
                }
            }
        });
        server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
        stacklessLogging.close();
    }

    @Test
    public void test404NoAccept() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));

        assertContent(response);
    }

    @Test
    public void test404EmptyAccept() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Accept: \r\n" +
                "Host: Localhost\r\n" +
                "\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), is(0));
        assertThat("Response Content-Type", response.getField(HttpHeader.CONTENT_TYPE), is(nullValue()));
    }

    @Test
    public void test404UnAccept() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Accept: text/*;q=0\r\n" +
                "Host: Localhost\r\n" +
                "\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        dump(response);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), is(0));
        assertThat("Response Content-Type", response.getField(HttpHeader.CONTENT_TYPE), is(nullValue()));
    }

    private void dump(HttpTester.Response response)
    {
        System.out.println("-------------");
        System.out.println(response);
        System.out.println(response.getContent());
        System.out.println();
    }

    @Test
    public void test404AllAccept() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: */*\r\n" +
                "\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));
        assertContent(response);
    }

    @Test
    public void test404HtmlAccept() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));
        assertContent(response);
    }

    @Test
    public void test404PostHttp10() throws Exception
    {
        String rawResponse = connector.getResponse(
            "POST / HTTP/1.0\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Content-Length: 10\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n" +
                "0123456789");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(404));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));
        assertThat(response.get(HttpHeader.CONNECTION), is("keep-alive"));
        assertContent(response);
    }

    @Test
    public void test404PostHttp11() throws Exception
    {
        String rawResponse = connector.getResponse(
            "POST / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Content-Length: 10\r\n" +
                "Connection: keep-alive\r\n" + // This is not need by HTTP/1.1 but sometimes sent anyway
                "\r\n" +
                "0123456789");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(404));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));
        assertThat(response.getField(HttpHeader.CONNECTION), nullValue());
        assertContent(response);
    }

    @Test
    public void test404PostCantConsumeHttp10() throws Exception
    {
        String rawResponse = connector.getResponse(
            "POST / HTTP/1.0\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Content-Length: 100\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n" +
                "0123456789");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(404));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));
        assertThat(response.getField(HttpHeader.CONNECTION), nullValue());
        assertContent(response);
    }

    @Test
    public void test404PostCantConsumeHttp11() throws Exception
    {
        String rawResponse = connector.getResponse(
            "POST / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Content-Length: 100\r\n" +
                "Connection: keep-alive\r\n" + // This is not need by HTTP/1.1 but sometimes sent anyway
                "\r\n" +
                "0123456789");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(404));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));
        assertThat(response.getField(HttpHeader.CONNECTION).getValue(), is("close"));
        assertContent(response);
    }

    @Test
    public void testMoreSpecificAccept() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html, some/other;specific=true\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));

        assertContent(response);
    }

    @Test
    public void test404HtmlAcceptAnyCharset() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Accept-Charset: *\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=UTF-8"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=UTF-8\""));

        assertContent(response);
    }

    @Test
    public void test404HtmlAcceptUtf8Charset() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Accept-Charset: utf-8\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=UTF-8"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=UTF-8\""));

        assertContent(response);
    }

    @Test
    public void test404HtmlAcceptNotUtf8Charset() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Accept-Charset: utf-8;q=0\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

//        System.out.println("response: " + response);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));

        assertContent(response);
    }

    @Test
    public void test404HtmlAcceptNotUtf8UnknownCharset() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Accept-Charset: utf-8;q=0,unknown\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), is(0));
        assertThat("Response Content-Type", response.getField(HttpHeader.CONTENT_TYPE), is(nullValue()));
    }

    @Test
    public void test404HtmlAcceptUnknownUtf8Charset() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Accept-Charset: utf-8;q=0.1,unknown\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=UTF-8"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=UTF-8\""));

        assertContent(response);
    }

    @Test
    public void test404PreferHtml() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html;q=1.0,text/json;q=0.5,*/*\r\n" +
                "Accept-Charset: *\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=UTF-8"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=UTF-8\""));

        assertContent(response);
    }

    @Test
    public void test404PreferJson() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html;q=0.5,text/json;q=1.0,*/*\r\n" +
                "Accept-Charset: *\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/json"));

        assertContent(response);
    }

    @Test
    public void testCharEncoding() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET /charencoding/foo HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/plain\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/plain"));

        assertContent(response);
    }

    @Test
    public void testBadMessage() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET /badmessage/444 HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(444));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));

        assertContent(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/jsonmessage/",
        "/xmlmessage/",
        "/htmlmessage/",
        "/utf8message/",
    })
    public void testComplexCauseMessageNoAcceptHeader(String path) throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET " + path + " HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(500));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));

        String content = assertContent(response);

        if (path.startsWith("/utf8"))
        {
            // we are Not expecting UTF-8 output, look for mangled ISO-8859-1 version
            assertThat("content", content, containsString("Euro is &amp;euro; and ? and %E2%82%AC"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/jsonmessage/",
        "/xmlmessage/",
        "/htmlmessage/",
        "/utf8message/",
    })
    public void testComplexCauseMessageAcceptUtf8Header(String path) throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET " + path + " HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/html\r\n" +
                "Accept-Charset: utf-8\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        System.out.println("response: " + response);

        assertThat("Response status code", response.getStatus(), is(500));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=UTF-8"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=UTF-8\""));

        String content = assertContent(response);

        if (path.startsWith("/utf8"))
        {
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharacters
            // we are Not expecting UTF-8 output, look for mangled ISO-8859-1 version
            assertThat("content", content, containsString("Euro is &amp;euro; and \u20AC and %E2%82%AC"));
            // @checkstyle-enabled-check : AvoidEscapedUnicodeCharacters
        }
    }

    private String assertContent(HttpTester.Response response) throws Exception
    {
        String contentType = response.get(HttpHeader.CONTENT_TYPE);
        String content = response.getContent();

        if (contentType.contains("text/html"))
        {
            assertThat(content, not(containsString("<script>")));
            assertThat(content, not(containsString("<glossary>")));
            assertThat(content, not(containsString("<!DOCTYPE>")));
            assertThat(content, not(containsString("&euro;")));

            // we expect that our generated output conforms to text/xhtml is well formed
            DocumentBuilderFactory xmlDocumentBuilderFactory = DocumentBuilderFactory.newInstance();
            xmlDocumentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = xmlDocumentBuilderFactory.newDocumentBuilder();
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
            {
                // We consider this content to be XML well formed if these 2 lines do not throw an Exception
                Document doc = db.parse(inputStream);
                doc.getDocumentElement().normalize();
            }
        }
        else if (contentType.contains("text/json"))
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> jo = (Map<String, Object>)new JSON().fromJSON(response.getContent());

            Set<String> acceptableKeyNames = new HashSet<>();
            acceptableKeyNames.add("url");
            acceptableKeyNames.add("status");
            acceptableKeyNames.add("message");
            acceptableKeyNames.add("servlet");
            acceptableKeyNames.add("cause0");
            acceptableKeyNames.add("cause1");
            acceptableKeyNames.add("cause2");

            for (Object key : jo.keySet())
            {
                String keyStr = (String)key;
                assertTrue(acceptableKeyNames.contains(keyStr), "Unexpected Key [" + keyStr + "]");

                Object value = jo.get(key);
                assertThat("Unexpected value type (" + value.getClass().getName() + ")",
                    value, instanceOf(String.class));
            }

            assertThat("url field", jo.get("url"), is(notNullValue()));
            String expectedStatus = String.valueOf(response.getStatus());
            assertThat("status field", jo.get("status"), is(expectedStatus));
            String message = (String)jo.get("message");
            assertThat("message field", message, is(notNullValue()));
            assertThat("message field", message, anyOf(
                not(containsString("<")),
                not(containsString(">"))));
        }
        else if (contentType.contains("text/plain"))
        {
            assertThat(content, containsString("STATUS: " + response.getStatus()));
        }
        else
        {
            System.out.println("Not checked Content-Type: " + contentType);
            System.out.println(content);
        }

        return content;
    }

    @Test
    public void testJsonResponse() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET /badmessage/444 HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/json\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(444));

        assertContent(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/jsonmessage/",
        "/xmlmessage/",
        "/htmlmessage/",
        "/utf8message/",
    })
    public void testJsonResponseWorse(String path) throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET " + path + " HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "Accept: text/json\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response status code", response.getStatus(), is(500));

        String content = assertContent(response);

        if (path.startsWith("/utf8"))
        {
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharacters
            // we are expecting UTF-8 output, look for it.
            assertThat("content", content, containsString("Euro is &amp;euro; and \u20AC and %E2%82%AC"));
            // @checkstyle-enable-check : AvoidEscapedUnicodeCharacters
        }
    }

    @Test
    public void testErrorContextRecycle() throws Exception
    {
        server.stop();
        ContextHandler context = new ContextHandler(server, "/foo");
        context.setErrorHandler(new ErrorHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().println("Context Error");
            }
        });
        context.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.sendError(444);
            }
        });

        context.setErrorHandler(new ErrorHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().println("Server Error");
            }
        });

        server.start();

        LocalConnector.LocalEndPoint connection = connector.connect();
        connection.addInputAndExecute(BufferUtil.toBuffer(
            "GET /foo/test HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "\r\n"));
        String response = connection.getResponse();

        assertThat(response, containsString("HTTP/1.1 444 444"));
        assertThat(response, containsString("Context Error"));

        connection.addInputAndExecute(BufferUtil.toBuffer(
            "GET /test HTTP/1.1\r\n" +
                "Host: Localhost\r\n" +
                "\r\n"));
        response = connection.getResponse();
        assertThat(response, containsString("HTTP/1.1 404 Not Found"));
        assertThat(response, containsString("Server Error"));
    }
}
