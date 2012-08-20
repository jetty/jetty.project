//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.util.SimpleHttpParser;
import org.eclipse.jetty.util.log.Log;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking
@RunWith(value = Parameterized.class)
public class HttpManyWaysToCommitTest extends AbstractHttpTest
{
    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]{{HttpVersion.HTTP_1_0.asString()}, {HttpVersion.HTTP_1_1.asString()}};
        return Arrays.asList(data);
    }

    public HttpManyWaysToCommitTest(String httpVersion)
    {
        super(httpVersion);
    }

    @Test
    public void testHandlerDoesNotSetHandled() throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(false));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 404", response.getCode(), is("404"));
    }

    @Test
    public void testHandlerDoesNotSetHandledAndThrow() throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertHeader(response, "content-length", "0");
    }

    @Test
    public void testHandlerSetsHandledTrueOnlyAndThrow() throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertHeader(response, "content-length", "6");
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow() throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();


        assertThat("response code is 200", response.getCode(), is("200"));
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandlerExplicitFlushAndThrow() throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandledAndFlushWithoutContentAndThrow() throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked"); // HTTP/1.0 does not do chunked.  it will just send content and close
    }

    @Test
    public void testWriteFlushWriteMoreAndThrow() throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");  // TODO HTTP/1.0 does not do chunked
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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        assertHeader(response, "content-length", "6");
    }

    @Test
    public void testBufferOverflowAndThrow() throws Exception
    {
        server.setHandler(new OverflowHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");

    }

    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow() throws Exception
    {
        Log.getRootLogger().setDebugEnabled(true);
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        //TODO: jetty ignores setContentLength and sends transfer-encoding header. Correct?
    }

    @Test
    public void testWriteAndSetContentLengthAndThrow() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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

        SimpleHttpParser.TestHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        // TODO: once flushed setting contentLength is ignored and chunked is used. Correct?
        if ("HTTP/1.1".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testWriteAndSetContentLengthTooSmallAndThrow() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(true));
        server.start();

        SimpleHttpParser.TestHttpResponse response = executeRequest();

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
}
