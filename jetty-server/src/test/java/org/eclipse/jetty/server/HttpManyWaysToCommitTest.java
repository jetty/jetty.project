//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumingThat;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking
public class HttpManyWaysToCommitTest extends AbstractHttpTest
{
    public static Stream<Arguments> httpVersions()
    {
        return Stream.of(
            HttpVersion.HTTP_1_0,
            HttpVersion.HTTP_1_1
        ).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerDoesNotSetHandled(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(404));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerDoesNotSetHandledAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(500));
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerSetsHandledTrueOnly(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        if (HttpVersion.HTTP_1_1.asString().equals(httpVersion))
            assertThat(response, containsHeaderValue("content-length", "0"));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerSetsHandledTrueOnlyAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new OnlySetHandledHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(500));
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerSetsHandledAndWritesSomeContent(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        assertThat(response, containsHeaderValue("content-length", "6"));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(500));
        assertThat("response body", response.getContent(), not(is("foobar")));
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerExplicitFlush(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerExplicitFlushAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new ExplicitFlushHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Since the 200 was committed, the 500 did not get the chance to be written
        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledAndFlushWithoutContent(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledAndFlushWithoutContentAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetHandledAndFlushWithoutContentHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledWriteFlushWriteMore(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledWriteFlushWriteMoreAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new WriteFlushWriteMoreHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Since the 200 was committed, the 500 did not get the chance to be written
        assertThat("response code", response.getStatus(), is(200));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledOverflow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new OverflowHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledOverflow2(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new Overflow2Handler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobarfoobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledOverflow3(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new Overflow3Handler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobarfoobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandledBufferOverflowAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new OverflowHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Response was committed when we throw, so 200 expected
        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        assumingThat(httpVersion == HttpVersion.HTTP_1_1,
            () -> assertThat(response, containsHeaderValue("transfer-encoding", "chunked")));
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
            response.setBufferSize(4);
            response.getWriter().write("foobar");
            super.handle(target, baseRequest, request, response);
        }
    }

    private class Overflow2Handler extends ThrowExceptionOnDemandHandler
    {
        private Overflow2Handler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setBufferSize(8);
            response.getWriter().write("fo");
            response.getWriter().write("obarfoobar");
            super.handle(target, baseRequest, request, response);
        }
    }

    private class Overflow3Handler extends ThrowExceptionOnDemandHandler
    {
        private Overflow3Handler(boolean throwException)
        {
            super(throwException);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setBufferSize(8);
            response.getWriter().write("fo");
            response.getWriter().write("ob");
            response.getWriter().write("ar");
            response.getWriter().write("fo");
            response.getWriter().write("ob");
            response.getWriter().write("ar");
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAnd304Status(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLength304Handler());
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);
        assertThat("response code", response.getStatus(), is(304));
        assertThat(response, containsHeaderValue("content-length", "32768"));
        byte[] content = response.getContentBytes();
        assertThat(content.length, is(0));
        assertFalse(response.isEarlyEOF());
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthFlushAndWriteInsufficientBytes(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteInsufficientBytesHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);
        assertThat("response code", response.getStatus(), is(200));
        assertThat(response, containsHeaderValue("content-length", "6"));
        byte[] content = response.getContentBytes();
        assertThat("content bytes", content.length, is(0));
        assertTrue(response.isEarlyEOF(), "response eof");
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAndWriteInsufficientBytes(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteInsufficientBytesHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);
        assertThat("response is error", response.getStatus(), is(500));
        assertFalse(response.isEarlyEOF(), "response not eof");
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAndFlushWriteInsufficientBytes(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteInsufficientBytesHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);
        assertThat("response has no status", response.getStatus(), is(200));
        assertTrue(response.isEarlyEOF(), "response eof");
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytes(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue("content-length", "3"));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteThatAmountOfBytesHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting the content-length and then writing the bytes commits the response
        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
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

    private class SetContentLength304Handler extends AbstractHandler
    {
        private SetContentLength304Handler()
        {
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(32768);
            response.setStatus(HttpStatus.NOT_MODIFIED_304);
        }
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

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAndWriteMoreBytes(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteMoreBytesHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue("content-length", "3"));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSetContentLengthAndWriteMoreAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new SetContentLengthAndWriteMoreBytesHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting the content-length and then writing the bytes commits the response
        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
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
            // Only "foo" will get written and "bar" will be discarded
            response.getWriter().write("foobar");
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testWriteAndSetContentLength(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue("content-length", "3"));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testWriteAndSetContentLengthAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Writing the bytes and then setting the content-length commits the response
        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
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
            response.setContentLength(3);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testWriteAndSetContentLengthTooSmall(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(false));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code", response.getStatus(), is(500));
        assertThat("response body", response.getContent(), not(is("foo")));
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testWriteAndSetContentLengthTooSmallAndThrow(HttpVersion httpVersion) throws Exception
    {
        server.setHandler(new WriteAndSetContentLengthTooSmallHandler(true));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code", response.getStatus(), is(500));
        assertThat("response body", response.getContent(), not(is("foo")));
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
