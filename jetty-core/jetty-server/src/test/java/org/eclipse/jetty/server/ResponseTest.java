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

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.SetCookieParser;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResponseTest
{
    private Server server;
    private LocalConnector connector;

    private static class MockResponse implements Response
    {
        private final Request _request;

        public MockResponse(Request request)
        {
            _request = request;
        }

        @Override
        public Request getRequest()
        {
            return _request;
        }

        @Override
        public int getStatus()
        {
            return 0;
        }

        @Override
        public void setStatus(int code)
        {

        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return null;
        }

        @Override
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return null;
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {

        }

        @Override
        public boolean isCommitted()
        {
            return false;
        }

        @Override
        public boolean hasLastWrite()
        {
            return false;
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            return false;
        }

        @Override
        public void reset()
        {

        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            return null;
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {

        }
    }

    private static class MockResponseWrapper extends Response.Wrapper
    {
        public MockResponseWrapper(Request request, Response wrapped)
        {
            super(request, wrapped);
        }
    }

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setIdleTimeout(60000);
        server.addConnector(connector);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
        connector = null;
    }

    @Test
    public void testAsInContext() throws Exception
    {
        // no response
        assertNull(Response.asInContext(null, MockResponseWrapper.class));

       // response has no request
       MockResponse mockResponse = new MockResponse(null);
       assertNull(Response.asInContext(mockResponse, MockResponseWrapper.class));

        // response with request with no context is the same as Response.as
        MockContext mockContext = new MockContext();
        MockRequest mockRequest = new MockRequest(mockContext);
        mockResponse  = new MockResponse(mockRequest);
        MockRequest.MockWrapper mockRequestWrapperA = new MockRequest.MockWrapper(mockRequest, mockContext);
        MockResponseWrapper mockResponseWrapperA = new MockResponseWrapper(mockRequestWrapperA, mockResponse);
        MockRequest.MockWrapper mockRequestWrapperB = new MockRequest.MockWrapper(mockRequestWrapperA, null);
        MockResponseWrapper mockResponseWrapperB = new MockResponseWrapper(mockRequestWrapperB, mockResponseWrapperA);
        Response asInContext = Response.asInContext(mockResponseWrapperB, MockResponseWrapper.class);
        Response as = Response.as(mockResponseWrapperB, MockResponseWrapper.class);
        assertSame(asInContext, as);
        assertSame(mockResponseWrapperB, asInContext);
        assertSame(mockResponseWrapperB, as);

        // response with request with context must not cross context boundary
        mockRequestWrapperB = new MockRequest.MockWrapper(mockRequestWrapperA, new MockContext());
        mockResponseWrapperB = new MockResponseWrapper(mockRequestWrapperB, mockResponseWrapperA);
        Response.Wrapper wrapperC = new Response.Wrapper(new MockRequest.MockWrapper(mockRequestWrapperB, new MockContext()), mockResponseWrapperB)
        {
        };
        assertNull(Response.asInContext(wrapperC, MockResponseWrapper.class));

        // response with request with same context in more than one wrap returns first matching class
        mockRequestWrapperB = new MockRequest.MockWrapper(mockRequestWrapperA, mockContext);
        mockResponseWrapperB = new MockResponseWrapper(mockRequestWrapperB, mockResponseWrapperA);
        wrapperC = new Response.Wrapper(new MockRequest.Wrapper(mockRequestWrapperB), mockResponseWrapperB)
        {
        };
        assertSame(mockResponseWrapperB, Response.asInContext(wrapperC, MockResponseWrapper.class));
    }

    @Test
    public void testGET() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testServerDateFieldsPersistent() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String date = response.getHeaders().get(HttpHeader.DATE);
                String server = response.getHeaders().get(HttpHeader.SERVER);

                response.getHeaders().add("Temp", "field");
                response.getHeaders().add("Test", "before reset");
                assertThrows(UnsupportedOperationException.class, () -> response.getHeaders().remove(HttpHeader.SERVER));
                assertThrows(UnsupportedOperationException.class, () -> response.getHeaders().remove(HttpHeader.DATE));
                response.getHeaders().remove("Temp");

                response.getHeaders().add("Temp", "field");
                Iterator<HttpField> iterator = response.getHeaders().iterator();
                assertThat(iterator.next().getHeader(), is(HttpHeader.SERVER));
                assertThrows(UnsupportedOperationException.class, iterator::remove);
                assertThat(iterator.next().getHeader(), is(HttpHeader.DATE));
                assertThrows(UnsupportedOperationException.class, iterator::remove);
                assertThat(iterator.next().getName(), is("Test"));
                assertThat(iterator.next().getName(), is("Temp"));
                iterator.remove();
                assertFalse(response.getHeaders().contains("Temp"));
                assertThrows(UnsupportedOperationException.class, () -> response.getHeaders().remove(HttpHeader.SERVER));
                assertFalse(iterator.hasNext());

                ListIterator<HttpField> listIterator = response.getHeaders().listIterator();
                assertThat(listIterator.next().getHeader(), is(HttpHeader.SERVER));
                assertThrows(UnsupportedOperationException.class, listIterator::remove);
                assertThat(listIterator.next().getHeader(), is(HttpHeader.DATE));
                assertThrows(UnsupportedOperationException.class, () -> listIterator.set(new HttpField("Something", "else")));
                listIterator.set(new HttpField(HttpHeader.DATE, "1970-01-01"));
                assertThat(listIterator.previous().getHeader(), is(HttpHeader.DATE));
                assertThrows(UnsupportedOperationException.class, listIterator::remove);
                assertThat(listIterator.previous().getHeader(), is(HttpHeader.SERVER));
                assertThrows(UnsupportedOperationException.class, listIterator::remove);
                assertThat(listIterator.next().getHeader(), is(HttpHeader.SERVER));
                assertThat(listIterator.next().getHeader(), is(HttpHeader.DATE));
                assertThrows(UnsupportedOperationException.class, listIterator::remove);
                listIterator.add(new HttpField("Temp", "value"));
                assertThat(listIterator.previous().getName(), is("Temp"));
                listIterator.remove();
                assertFalse(response.getHeaders().contains("Temp"));

                response.getHeaders().add("Temp", "field");
                response.getHeaders().put(HttpHeader.DATE, "1970-02-02");

                response.reset();

                assertThat(response.getHeaders().get(HttpHeader.DATE), is(date));
                assertThat(response.getHeaders().get(HttpHeader.SERVER), is(server));

                response.getHeaders().add("Test", "after reset");

                response.getHeaders().putDate("Date", 1L);
                assertThrows(UnsupportedOperationException.class, () -> response.getHeaders().put(HttpHeader.SERVER, (String)null));
                response.getHeaders().put(HttpHeader.SERVER, "jettyrocks");
                assertThrows(UnsupportedOperationException.class, () -> response.getHeaders().put(HttpHeader.SERVER, (String)null));
                callback.succeeded();
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.get(HttpHeader.SERVER), notNullValue());
        assertThat(response.get(HttpHeader.DATE), notNullValue());
        assertThat(response.get("Test"), is("after reset"));
    }

    @Test
    public void testRedirectGET() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(false);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));

        request = """
                GET /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));
    }

    @Test
    public void testEncodedRedirectGET() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
                .getHttpConfiguration().setRelativeRedirectAllowed(false);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "somewh%65r%65?else+entirely");
                return true;
            }
        });
        server.start();

        String request = """
                GET /p%61th/ HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/p%61th/somewh%65r%65?else+entirely"));
    }

    @Test
    public void testRelativeRedirectGET() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));

        request = """
                GET /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));
    }

    @Test
    public void testRequestRelativeRedirectGET() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));

        request = """
                GET /path/ HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/path/somewhere/else"));

        request = """
                GET /path/index.html HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/path/somewhere/else"));

        request = """
                GET /path/to/ HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/path/to/somewhere/else"));
    }

    @Test
    public void testRedirectPOST() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(false);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                POST /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));

        request = """
                POST /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname/somewhere/else"));
    }

    @Test
    public void testRelativeRedirectPOST() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setRelativeRedirectAllowed(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                POST /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));

        request = """
                POST /path HTTP/1.1\r
                Host: hostname\r
                Connection: close\r
                \r
                """;
        response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("/somewhere/else"));
    }

    public static Stream<Arguments> redirectComplianceTest()
    {
        return Stream.of(
            Arguments.of(null, "http://[bad]:xyz/", HttpStatus.FOUND_302, null),
            Arguments.of(UriCompliance.UNSAFE, "http://[bad]:xyz/", HttpStatus.INTERNAL_SERVER_ERROR_500, "Bad authority"),
            Arguments.of(UriCompliance.DEFAULT, "http://[bad]:xyz/", HttpStatus.INTERNAL_SERVER_ERROR_500, "Bad authority"),
            Arguments.of(null, "http://user:password@host.com/", HttpStatus.FOUND_302, null),
            Arguments.of(UriCompliance.DEFAULT, "http://user:password@host.com/", HttpStatus.INTERNAL_SERVER_ERROR_500, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "http://user:password@host.com/", HttpStatus.FOUND_302, null),
            Arguments.of(null, "http://host.com/very%2Funsafe", HttpStatus.FOUND_302, null),
            Arguments.of(UriCompliance.LEGACY, "http://host.com/very%2Funsafe", HttpStatus.FOUND_302, null),
            Arguments.of(UriCompliance.DEFAULT, "http://host.com/very%2Funsafe", HttpStatus.INTERNAL_SERVER_ERROR_500, "Ambiguous")
        );
    }

    @ParameterizedTest
    @MethodSource("redirectComplianceTest")
    public void testRedirectCompliance(UriCompliance compliance, String location, int status, String content) throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setRedirectUriCompliance(compliance);
            server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setRelativeRedirectAllowed(true);
            server.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    Response.sendRedirect(request, response, callback, location);
                    return true;
                }
            });
            server.start();

            String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
            HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
            assertThat(response.getStatus(), is(status));
            if (HttpStatus.isRedirection(status))
                assertThat(response.get(HttpHeader.LOCATION), is(location));
            if (content != null)
                assertThat(response.getContent(), containsString(content));
        }
    }

    @Test
    public void testAuthorityUserNotAllowedWithNonRelativeRedirect() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setRelativeRedirectAllowed(false);
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setRedirectUriCompliance(UriCompliance.DEFAULT);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Response.sendRedirect(request, response, callback, "/somewhere/else");
                return true;
            }
        });
        server.start();

        String request = """
                GET http://user:password@hostname:8888/path HTTP/1.0\r
                Host: hostname:8888\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.MOVED_TEMPORARILY_302, response.getStatus());
        assertThat(response.get(HttpHeader.LOCATION), is("http://hostname:8888/somewhere/else"));
    }

    @Test
    public void testXPoweredByDefault() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setSendXPoweredBy(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, "Test", callback);
                return true;
            }
        });
        server.start();

        String request = """
                GET /test HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // ensure there are only 1 entry for each of these headers
        List<HttpHeader> expectedHeaders = List.of(HttpHeader.SERVER, HttpHeader.X_POWERED_BY, HttpHeader.DATE, HttpHeader.CONTENT_LENGTH);
        for (HttpHeader expectedHeader: expectedHeaders)
        {
            List<String> actualHeader = response.getValuesList(expectedHeader);
            assertThat(expectedHeader + " exists", actualHeader, is(notNullValue()));
            assertThat(expectedHeader + " header count", actualHeader.size(), is(1));
        }
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("4"));
        assertThat(response.get(HttpHeader.X_POWERED_BY), is(HttpConfiguration.SERVER_VERSION));
    }

    @Test
    public void testXPoweredByOverride() throws Exception
    {
        server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class)
            .getHttpConfiguration().setSendXPoweredBy(true);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // replace the X-Powered-By value
                response.getHeaders().put(HttpHeader.X_POWERED_BY, "SomeServer");
                Content.Sink.write(response, true, "Test", callback);
                return true;
            }
        });
        server.start();

        String request = """
                GET /test HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // ensure there are only 1 entry for each of these headers
        List<HttpHeader> expectedHeaders = List.of(HttpHeader.SERVER, HttpHeader.X_POWERED_BY, HttpHeader.DATE, HttpHeader.CONTENT_LENGTH);
        for (HttpHeader expectedHeader: expectedHeaders)
        {
            List<String> actualHeader = response.getValuesList(expectedHeader);
            assertThat(expectedHeader + " exists", actualHeader, is(notNullValue()));
            assertThat(expectedHeader + " header count", actualHeader.size(), is(1));
        }
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("4"));
        assertThat(response.get(HttpHeader.X_POWERED_BY), is("SomeServer"));
    }

    @Test
    public void testHttpCookieProcessing() throws Exception
    {
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addHttpStreamWrapper(httpStream -> new HttpStream.Wrapper(httpStream)
                {
                    @Override
                    public void prepareResponse(HttpFields.Mutable headers)
                    {
                        super.prepareResponse(headers);
                        for (ListIterator<HttpField> i = headers.listIterator(); i.hasNext();)
                        {
                            HttpField field = i.next();
                            if (field.getHeader() != HttpHeader.SET_COOKIE)
                                continue;

                            HttpCookie cookie = SetCookieParser.newInstance().parse(field.getValue());

                            i.set(new HttpCookieUtils.SetCookieHttpField(
                                HttpCookie.build(cookie)
                                    .domain("customized")
                                    .sameSite(HttpCookie.SameSite.LAX)
                                    .build(),
                                request.getConnectionMetaData().getHttpConfiguration().getResponseCookieCompliance()));
                        }
                    }
                });
                response.setStatus(200);
                Response.addCookie(response, HttpCookie.from("name", "test1"));
                response.getHeaders().add(HttpHeader.SET_COOKIE, "other=test2; Domain=original; SameSite=None; Attr=x");
                Content.Sink.write(response, true, "OK", callback);
                return true;
            }
        });
        server.start();

        String request = """
                POST /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getValuesList(HttpHeader.SET_COOKIE), containsInAnyOrder(
            "name=test1; Domain=customized; SameSite=Lax",
            "other=test2; Domain=customized; SameSite=Lax; Attr=x"));
    }
}
