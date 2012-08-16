//========================================================================
//Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking
@RunWith(value = Parameterized.class)
public class HttpManyWaysToCommitTest
{
    private static Server server;
    private static SelectChannelConnector connector;
    private String httpVersion;

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]{{HttpVersion.HTTP_1_0.asString()}, {HttpVersion.HTTP_1_1.asString()}};
        return Arrays.asList(data);
    }

    public HttpManyWaysToCommitTest(String httpVersion)
    {
        this.httpVersion = httpVersion;
    }

    @Before
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector(server);
        server.setConnectors(new Connector[]{connector});
        ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(true);
    }

    /* ------------------------------------------------------------ */
    @After
    public void tearDown() throws Exception
    {
        ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(false);
        server.stop();
    }

    @Test
    public void testHandlerDoesNotSetHandled() throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 404", response.getCode(), is("404"));
    }

    @Test
    public void testHandlerDoesNotSetHandledAndThrow() throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
    }

    private class DoesNotSetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private DoesNotSetHandledHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(false); // not needed, but lets be explicit about what the test does
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandlerSetsHandledTrueOnly() throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertHeader(response, "content-length", "0");
    }

    @Test
    public void testHandlerSetsHandledTrueOnlyAndThrow() throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
    }


    private class OnlySetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private OnlySetHandledHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContent() throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertHeader(response, "content-length", "6");
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow() throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(true));
        server.start();

        Response response = executeRequest();

        // This assertion is currently red. However I think it should be green. Nothing has been flushed to the
        // client yet, when we throw an exception in the handler. So I expect HttpChannel to send a 500.
        // Fails in HttpConnection line 619/620
        assertThat("response code is 500", response.getCode(), is("500"));
    }

    private class SetHandledWriteSomeDataHandler extends ThrowExceptionOnDemandHandler
    {
        private SetHandledWriteSomeDataHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandlerExplicitFlush() throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(false));
        server.start();

        Response response = executeRequest();


        assertThat("response code is 200", response.getCode(), is("200"));
        // assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandlerExplicitFlushAndThrow() throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // assertHeader(response, "transfer-encoding", "chunked");
    }

    private class ExplicitFlushHandler extends ThrowExceptionOnDemandHandler
    {
        private ExplicitFlushHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            response.flushBuffer();
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandledAndFlushWithoutContent() throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandledAndFlushWithoutContentAndThrow() throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // assertHeader(response, "transfer-encoding", "chunked");
    }

    private class SetHandledAndFlushWithoutContentHandler extends ThrowExceptionOnDemandHandler
    {
        private SetHandledAndFlushWithoutContentHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.flushBuffer();
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testWriteFlushWriteMore() throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // assertHeader(response, "transfer-encoding", "chunked"); // HTTP/1.0 does not do chunked.  it will just send content and close
    }

    @Test
    public void testWriteFlushWriteMoreAndThrow() throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // assertHeader(response, "transfer-encoding", "chunked");  // TODO HTTP/1.0 does not do chunked
    }

    private class WriteFlushWriteMoreHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteFlushWriteMoreHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foo");
            response.flushBuffer();
            response.getWriter().write("bar");
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testBufferOverflow() throws Exception
    {
        server.setHandler(new OverflowHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        assertHeader(response, "content-length", "6");
    }

    @Test
    public void testBufferOverflowAndThrow() throws Exception
    {
        server.setHandler(new OverflowHandler(true));
        server.start();

        Response response = executeRequest();

        // response not committed when we throw, so 500 expected
        assertThat("response code is 500", response.getCode(), is("500"));
    }

    private class OverflowHandler extends ThrowExceptionOnDemandHandler
    {
        private OverflowHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setBufferSize(3);
            response.getWriter().write("foobar");
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytes() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");

    }

    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(true));
        server.start();

        Response response = executeRequest();

        //TODO: should we expect 500 here?
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
    }

    private class SetContentLengthAndWriteThatAmountOfBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private SetContentLengthAndWriteThatAmountOfBytesHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(3);
            response.getWriter().write("foo");
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testSetContentLengthAndWriteMoreBytes() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteMoreBytesHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // jetty truncates the body when content-length is reached.! This is correct and desired behaviour?
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
    }

    @Test
    public void testSetContentLengthAndWriteMoreAndThrow() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteMoreBytesHandler(true));
        server.start();

        Response response = executeRequest();

        // TODO: we throw before response is committed. should we expect 500?
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
    }

    private class SetContentLengthAndWriteMoreBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private SetContentLengthAndWriteMoreBytesHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(3);
            response.getWriter().write("foobar"); // Only "foo" will get written and "bar" will be discarded
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testWriteAndSetContentLength() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        //TODO: jetty ignores setContentLength and sends transfer-encoding header. Correct?
        // assertHeader(response,"transfer-encoding","chunked");
    }

    @Test
    public void testWriteAndSetContentLengthAndThrow() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
    }

    private class WriteAndSetContentLengthHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteAndSetContentLengthHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foo");
            response.setContentLength(3); // This should commit the response
            super.handle(target, baseRequest, request, response);
        }
    }

    @Test
    @Ignore
    public void testWriteAndSetContentLengthTooSmall() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(false));
        server.start();

        Response response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        // TODO: once flushed setting contentLength is ignored and chunked is used. Correct?
        // assertHeader(response,"transfer-encoding","chunked");
    }

    @Test
    public void testWriteAndSetContentLengthTooSmallAndThrow() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(true));
        server.start();

        Response response = executeRequest();

        assertThat(response.getCode(), is("500"));
    }

    private class WriteAndSetContentLengthTooSmallHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteAndSetContentLengthTooSmallHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            response.setContentLength(3); 
            super.handle(target, baseRequest, request, response);
        }
    }

    private Response executeRequest() throws URISyntaxException, IOException
    {
        Socket socket = new Socket("localhost", connector.getLocalPort());
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));
        String request = "GET / " + httpVersion + "\r\n";

        writer.write(request);
        writer.write("Host: localhost");
        writer.println("\r\n");
        writer.flush();

        Response response = readResponse(reader);
        return response;
    }

    private Response readResponse(BufferedReader reader) throws IOException
    {
        // Simplified parser for HTTP responses
        String line = reader.readLine();
        if (line == null)
            throw new EOFException();
        Matcher responseLine = Pattern.compile("HTTP/1.1" + "\\s+(\\d+)").matcher(line);
        assertThat("http version is 1.1", responseLine.lookingAt(), is(true));
        String code = responseLine.group(1);

        Map<String, String> headers = new LinkedHashMap<>();
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;

            parseHeader(line, headers);
        }

        StringBuilder body;
        if (headers.containsKey("content-length"))
        {
            body = parseContentLengthDelimitedBody(reader, headers);
        }
        else if ("chunked".equals(headers.get("transfer-encoding")))
        {
            body = parseChunkedBody(reader);
        }
        else
        {
            body = parseEOFDelimitedBody(reader, headers);
        }

        return new Response(code, headers, body.toString().trim());
    }

    private void parseHeader(String line, Map<String, String> headers)
    {
        Matcher header = Pattern.compile("([^:]+):\\s*(.*)").matcher(line);
        assertTrue(header.lookingAt());
        String headerName = header.group(1);
        String headerValue = header.group(2);
        headers.put(headerName.toLowerCase(), headerValue.toLowerCase());
    }

    private StringBuilder parseContentLengthDelimitedBody(BufferedReader reader, Map<String, String> headers) throws IOException
    {
        StringBuilder body;
        int readLen = 0;
        int length = Integer.parseInt(headers.get("content-length"));
        body = new StringBuilder(length);
        try
        {
            //TODO: UTF-8 reader from joakim
            for (int i = 0; i < length; ++i)
            {
                char c = (char)reader.read();
                body.append(c);
                readLen++;
            }

        }
        catch (SocketTimeoutException e)
        {
            System.err.printf("Read %,d bytes (out of an expected %,d bytes)%n", readLen, length);
            throw e;
        }
        return body;
    }

    private StringBuilder parseChunkedBody(BufferedReader reader) throws IOException
    {
        StringBuilder body;
        String line;
        body = new StringBuilder(64 * 1024);
        while ((line = reader.readLine()) != null)
        {
            if ("0".equals(line))
            {
                line = reader.readLine();
                assertThat("There's no more content after as 0 indicated the final chunk", line, is(""));
                break;
            }

            int length = Integer.parseInt(line, 16);
            //TODO: UTF-8 reader from joakim
            for (int i = 0; i < length; ++i)
            {
                char c = (char)reader.read();
                body.append(c);
            }
            line = reader.readLine();
            // assertThat("chunk is followed by an empty line", line, is("")); //TODO: is this right? - NO.  Don't think you can really do chunks with readline generally, but maybe for this test is OK.
        }
        return body;
    }

    private StringBuilder parseEOFDelimitedBody(BufferedReader reader, Map<String, String> headers) throws IOException
    {
        StringBuilder body;
        if ("HTTP/1.1".equals(httpVersion))
            assertThat("if no content-length or transfer-encoding header is set, " +
                    "connection: close header must be set", headers.get("connection"),
                    is("close"));

        // read until EOF
        body = new StringBuilder();
        while (true)
        {
            //TODO: UTF-8 reader from joakim
            int read = reader.read();
            if (read == -1)
                break;
            char c = (char)read;
            body.append(c);
        }
        return body;
    }

    private void assertResponseBody(Response response, String expectedResponseBody)
    {
        assertThat("response body is" + expectedResponseBody, response.getBody(), is(expectedResponseBody));
    }

    private void assertHeader(Response response, String headerName, String expectedValue)
    {
        assertThat(headerName + "=" + expectedValue, response.getHeaders().get(headerName), is(expectedValue));
    }

    private class Response
    {
        private final String code;
        private final Map<String, String> headers;
        private final String body;

        private Response(String code, Map<String, String> headers, String body)
        {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        public String getCode()
        {
            return code;
        }

        public Map<String, String> getHeaders()
        {
            return headers;
        }

        public String getBody()
        {
            return body;
        }

        @Override
        public String toString()
        {
            return "Response{" +
                    "code='" + code + '\'' +
                    ", headers=" + headers +
                    ", body='" + body + '\'' +
                    '}';
        }
    }

    private class ThrowExceptionOnDemandHandler extends AbstractHandler
    {
        private final boolean throwException;

        private ThrowExceptionOnDemandHandler(boolean throwException)
        {
            this.throwException = throwException;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (throwException)
                throw new TestCommitException();
        }
    }

    private static class TestCommitException extends IllegalStateException
    {
        public TestCommitException()
        {
            super("Thrown by test");
        }
    }
}
