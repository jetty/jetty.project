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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

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

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 404", response.getCode(), is("404"));
    }

    @Test
    public void testHandlerDoesNotSetHandledAndThrow() throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
    }

    private class DoesNotSetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private DoesNotSetHandledHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(false); // not needed, but lets be explicit about what the test does
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandlerSetsHandledTrueOnly() throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        
        if (HttpVersion.HTTP_1_1.asString().equals(httpVersion))
            assertHeader(response, "content-length", "0");
    }

    @Test
    public void testHandlerSetsHandledTrueOnlyAndThrow() throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
    }

    private class OnlySetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private OnlySetHandledHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContent() throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        assertHeader(response, "content-length", "6");
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow() throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("response body is not foobar", response.getBody(), not(is("foobar")));
    }

    private class SetHandledWriteSomeDataHandler extends ThrowExceptionOnDemandHandler
    {
        private SetHandledWriteSomeDataHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandlerExplicitFlush() throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandlerExplicitFlushAndThrow() throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Since the 200 was committed, the 500 did not get the chance to be written
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foobar", response.getBody(), is("foobar"));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    private class ExplicitFlushHandler extends ThrowExceptionOnDemandHandler
    {
        private ExplicitFlushHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            response.flushBuffer();
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandledAndFlushWithoutContent() throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandledAndFlushWithoutContentAndThrow() throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    private class SetHandledAndFlushWithoutContentHandler extends ThrowExceptionOnDemandHandler
    {
        private SetHandledAndFlushWithoutContentHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.flushBuffer();
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandledWriteFlushWriteMore() throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandledWriteFlushWriteMoreAndThrow() throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Since the 200 was committed, the 500 did not get the chance to be written
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response code is 200", response.getCode(), is("200"));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    private class WriteFlushWriteMoreHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteFlushWriteMoreHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foo");
            response.flushBuffer();
            response.getWriter().write("bar");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testHandledOverflow() throws Exception
    {
        server.setHandler(new OverflowHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }
    
    @Test
    public void testHandledOverflow2() throws Exception
    {
        server.setHandler(new Overflow2Handler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobarfoobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }
    
    @Test
    public void testHandledOverflow3() throws Exception
    {
        server.setHandler(new Overflow3Handler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobarfoobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandledBufferOverflowAndThrow() throws Exception
    {
        server.setHandler(new OverflowHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Response was committed when we throw, so 200 expected
        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    private class OverflowHandler extends ThrowExceptionOnDemandHandler
    {
        private OverflowHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setBufferSize(4);
            response.getWriter().write("foobar");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    private class Overflow2Handler extends ThrowExceptionOnDemandHandler
    {
        private Overflow2Handler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setBufferSize(8);
            response.getWriter().write("fo");
            response.getWriter().write("obarfoobar");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }
    
    private class Overflow3Handler extends ThrowExceptionOnDemandHandler
    {
        private Overflow3Handler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setBufferSize(8);
            response.getWriter().write("fo");
            response.getWriter().write("ob");
            response.getWriter().write("ar");
            response.getWriter().write("fo");
            response.getWriter().write("ob");
            response.getWriter().write("ar");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testSetContentLengthFlushAndWriteInsufficientBytes() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteInsufficientBytesHandler(true));
        server.start();
        try
        {
            // TODO This test is compromised by the SimpleHttpResponse mechanism.
            // Replace with a better client

            SimpleHttpResponse response = executeRequest();
            String failed_body = ""+(char)-1+(char)-1+(char)-1;
            assertThat("response code is 200", response.getCode(), is("200"));
            assertThat(response.getBody(), Matchers.endsWith(failed_body));
            assertHeader(response, "content-length", "6");
        }
        catch(EOFException e)
        {
            // possible good response
        }
    }

    @Test
    public void testSetContentLengthAndWriteInsufficientBytes() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteInsufficientBytesHandler(false));
        server.start();

        try
        {
            // TODO This test is compromised by the SimpleHttpResponse mechanism.
            // Replace with a better client
            SimpleHttpResponse response = executeRequest();
            String failed_body = ""+(char)-1+(char)-1+(char)-1;
            assertThat("response code is 200", response.getCode(), is("200"));
            assertThat(response.getBody(), Matchers.endsWith(failed_body));
            assertHeader(response, "content-length", "6");
        }
        catch(EOFException e)
        {
            // expected
        }
    }
    
    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytes() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
    }

    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Setting the content-length and then writing the bytes commits the response
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
    }

    private class SetContentLengthAndWriteInsufficientBytesHandler extends AbstractHandler
    {
        boolean flush;
        private SetContentLengthAndWriteInsufficientBytesHandler(boolean flush)
        {
            this.flush = flush;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(6);
            if (flush)
                response.flushBuffer();
            response.getWriter().write("foo");
        }
    }
    
    private class SetContentLengthAndWriteThatAmountOfBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private SetContentLengthAndWriteThatAmountOfBytesHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(3);
            response.getWriter().write("foo");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testSetContentLengthAndWriteMoreBytes() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteMoreBytesHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
    }

    @Test
    public void testSetContentLengthAndWriteMoreAndThrow() throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteMoreBytesHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Setting the content-length and then writing the bytes commits the response
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
    }

    private class SetContentLengthAndWriteMoreBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private SetContentLengthAndWriteMoreBytesHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(3);
            // Only "foo" will get written and "bar" will be discarded
            response.getWriter().write("foobar");
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testWriteAndSetContentLength() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
    }

    @Test
    public void testWriteAndSetContentLengthAndThrow() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Writing the bytes and then setting the content-length commits the response
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
    }

    private class WriteAndSetContentLengthHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteAndSetContentLengthHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foo");
            response.setContentLength(3);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @Test
    public void testWriteAndSetContentLengthTooSmall() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("response body is not foo", response.getBody(), not(is("foo")));
    }

    @Test
    public void testWriteAndSetContentLengthTooSmallAndThrow() throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(true));
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("response body is not foo", response.getBody(), not(is("foo")));
    }

    private class WriteAndSetContentLengthTooSmallHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteAndSetContentLengthTooSmallHandler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            response.setContentLength(3);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }
}
