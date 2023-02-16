//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.ReHandlingErrorHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
        stacklessLogging = new StacklessLogging(HttpChannelState.class);
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                if (pathInContext.startsWith("/badmessage/"))
                {
                    int code = Integer.parseInt(pathInContext.substring(pathInContext.lastIndexOf('/') + 1));
                    throw new BadMessageException(code);
                }

                // produce an exception with an JSON formatted cause message
                if (pathInContext.startsWith("/jsonmessage/"))
                {
                    String message = "\"}, \"glossary\": {\n \"title\": \"example\"\n }\n {\"";
                    throw new TestException(message);
                }

                // produce an exception with an XML cause message
                if (pathInContext.startsWith("/xmlmessage/"))
                {
                    String message =
                        "<!DOCTYPE glossary PUBLIC \"-//OASIS//DTD DocBook V3.1//EN\">\n" +
                            " <glossary>\n" +
                            "  <title>example glossary</title>\n" +
                            " </glossary>";
                    throw new TestException(message);
                }

                // produce an exception with an HTML cause message
                if (pathInContext.startsWith("/htmlmessage/"))
                {
                    String message = "<hr/><script>alert(42)</script>%3Cscript%3E";
                    throw new TestException(message);
                }

                // produce an exception with a UTF-8 cause message
                if (pathInContext.startsWith("/utf8message/"))
                {
                    // @checkstyle-disable-check : AvoidEscapedUnicodeCharacters
                    String message = "Euro is &euro; and \u20AC and %E2%82%AC";
                    // @checkstyle-enable-check : AvoidEscapedUnicodeCharacters
                    throw new TestException(message);
                }

                // 200 response
                if (pathInContext.startsWith("/ok/"))
                {
                    Content.Sink.write(
                        response,
                        true,
                        "%s Error %s : %s%n".formatted(pathInContext, request.getAttribute(ErrorHandler.ERROR_STATUS), request.getAttribute(ErrorHandler.ERROR_MESSAGE)),
                        callback);
                    return true;
                }

                Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                return true;
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

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), is(0));
        assertThat("Response Content-Type", response.getField(HttpHeader.CONTENT_TYPE), is(nullValue()));
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

        assertThat("Response status code", response.getStatus(), is(404));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), is(0));
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
        assertThat(response.getContent(), containsString("<title>Error 404 Not Found</title>"));

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
    public void testThrowBadMessage() throws Exception
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

    @Test
    public void testBadMessage() throws Exception
    {
        String rawResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host:\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(400));
        assertThat("Response Content-Length", response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat("Response Content-Type", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html;charset=ISO-8859-1"));
        assertThat(response.getContent(), containsString("content=\"text/html;charset=ISO-8859-1\""));

        assertContent(response);
    }

    @Test
    public void testNoBodyErrorHandler() throws Exception
    {
        server.setErrorHandler((request, response, callback) ->
        {
            response.getHeaders().put(HttpHeader.LOCATION, "/error");
            response.getHeaders().put("X-Error-Message", String.valueOf(request.getAttribute(ErrorHandler.ERROR_MESSAGE)));
            response.getHeaders().put("X-Error-Status", Integer.toString(response.getStatus()));
            response.setStatus(302);
            response.write(true, null, callback);
            return true;
        });
        String rawResponse = connector.getResponse("""
                GET /no/host HTTP/1.1
                
                """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(302));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), is(0));
        assertThat(response.get(HttpHeader.LOCATION), is("/error"));
        assertThat(response.get("X-Error-Status"), is("400"));
        assertThat(response.get("X-Error-Message"), is("No Host"));
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
            acceptableKeyNames.add("cause0");
            acceptableKeyNames.add("cause1");
            acceptableKeyNames.add("cause2");

            for (String key : jo.keySet())
            {
                assertTrue(acceptableKeyNames.contains(key), "Unexpected Key [" + key + "]");

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
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        ContextHandler context = new ContextHandler("/foo");
        contexts.addHandler(context);
        context.setErrorHandler(new ErrorHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, BufferUtil.toBuffer("Context Error"), callback);
                return true;
            }
        });
        context.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.writeError(request, response, callback, 444);
                return true;
            }
        });

        server.setErrorHandler(new ErrorHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, BufferUtil.toBuffer("Server Error"), callback);
                return true;
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

    @Test
    public void testRootReHandlingErrorProcessor() throws Exception
    {
        ReHandlingErrorHandler.ByHttpStatus errorHandler = new ReHandlingErrorHandler.ByHttpStatus(server);
        errorHandler.put(400, "/ok/badMessage");
        server.setErrorHandler(errorHandler);

        String rawResponse = connector.getResponse("""
                GET /no/host HTTP/1.1
                
                """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("/ok/badMessage Error 400 : No Host"));
    }

    @Test
    public void testRootReHandlingErrorProcessorLoop() throws Exception
    {
        ReHandlingErrorHandler.ByHttpStatus errorHandler = new ReHandlingErrorHandler.ByHttpStatus(server);
        errorHandler.put(404, "/not/found");
        server.setErrorHandler(errorHandler);

        String rawResponse = connector.getResponse("""
                GET /not/found HTTP/1.1
                Host: localhost
                
                """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(404));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("<title>Error 404 Not Found</title>"));
    }

    @Test
    public void testRootReHandlingErrorProcessorExceptionLoop() throws Exception
    {
        ReHandlingErrorHandler.ByHttpStatus errorHandler = new ReHandlingErrorHandler.ByHttpStatus(server);
        errorHandler.put(444, "/badmessage/444");
        server.setErrorHandler(errorHandler);

        String rawResponse = connector.getResponse("""
                GET /badmessage/444 HTTP/1.1
                Host: localhost
                
                """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(444));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("<title>Error 444</title>"));
    }

    @Test
    public void testContextReHandlingErrorProcessor() throws Exception
    {
        server.stop();

        ContextHandler context = new ContextHandler("/ctx");
        context.setHandler(server.getHandler());

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.addHandler(context);
        server.setHandler(contexts);

        server.setErrorHandler(new ErrorHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
               throw new UnsupportedOperationException();
            }
        });

        server.start();
        ReHandlingErrorHandler.ByHttpStatus errorHandler = new ReHandlingErrorHandler.ByHttpStatus(context);
        errorHandler.put(444, "/ok/badMessage");
        context.setErrorHandler(errorHandler);

        String rawResponse = connector.getResponse("""
                GET /ctx/badmessage/444 HTTP/1.1
                Host: localhost
                
                """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("/ok/badMessage Error 444"));
    }

    static class TestException extends RuntimeException implements QuietException
    {
        public TestException(String message)
        {
            super(message);
        }
    }
}
