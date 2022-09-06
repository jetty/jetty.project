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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking
public class HttpManyWaysToAsyncCommitTest extends AbstractHttpTest
{
    private final String contextAttribute = getClass().getName() + ".asyncContext";

    public static Stream<Arguments> httpVersion()
    {
        // boolean dispatch - if true we dispatch, otherwise we complete
        final boolean DISPATCH = true;
        final boolean COMPLETE = false;
        final boolean IN_WAIT = true;
        final boolean WHILE_DISPATCHED = false;

        List<Arguments> ret = new ArrayList<>();
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, DISPATCH, IN_WAIT));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, DISPATCH, IN_WAIT));
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, COMPLETE, IN_WAIT));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, COMPLETE, IN_WAIT));
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, DISPATCH, WHILE_DISPATCHED));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, DISPATCH, WHILE_DISPATCHED));
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, COMPLETE, WHILE_DISPATCHED));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, COMPLETE, WHILE_DISPATCHED));
        return ret.stream();
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerDoesNotSetHandled(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        DoesNotSetHandledHandler handler = new DoesNotSetHandledHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(404));
        assertThat(handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerDoesNotSetHandledAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        DoesNotSetHandledHandler handler = new DoesNotSetHandledHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response;
        if (inWait)
        {
            // exception thrown and handled before any async processing
            response = executeRequest(httpVersion);
        }
        else
        {
            // exception thrown after async processing, so cannot be handled
            try (StacklessLogging log = new StacklessLogging(HttpChannelState.class))
            {
                response = executeRequest(httpVersion);
            }
        }

        assertThat(response.getStatus(), is(500));
        assertThat(handler.failure(), is(nullValue()));
    }

    private class DoesNotSetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private DoesNotSetHandledHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    if (dispatch)
                        asyncContext.dispatch();
                    else
                        asyncContext.complete();
                });
            }
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledTrueOnly(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        OnlySetHandledHandler handler = new OnlySetHandledHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "0"));
        assertThat(handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledTrueOnlyAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        OnlySetHandledHandler handler = new OnlySetHandledHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response;
        if (inWait)
        {
            // exception thrown and handled before any async processing
            response = executeRequest(httpVersion);
        }
        else
        {
            // exception thrown after async processing, so cannot be handled
            try (StacklessLogging log = new StacklessLogging(HttpChannelState.class))
            {
                response = executeRequest(httpVersion);
            }
        }

        assertThat(response.getStatus(), is(500));
        assertThat(handler.failure(), is(nullValue()));
    }

    private class OnlySetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private OnlySetHandledHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    if (dispatch)
                        asyncContext.dispatch();
                    else
                        asyncContext.complete();
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledAndWritesSomeContent(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetHandledWriteSomeDataHandler handler = new SetHandledWriteSomeDataHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "6"));
        assertThat(handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetHandledWriteSomeDataHandler handler = new SetHandledWriteSomeDataHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();
        HttpTester.Response response;
        if (inWait)
        {
            // exception thrown and handled before any async processing
            response = executeRequest(httpVersion);
        }
        else
        {
            // exception thrown after async processing, so cannot be handled
            try (StacklessLogging log = new StacklessLogging(HttpChannelState.class))
            {
                response = executeRequest(httpVersion);
            }
        }

        assertThat(response.getStatus(), is(500));
        assertThat(handler.failure(), is(nullValue()));
    }

    private class SetHandledWriteSomeDataHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private SetHandledWriteSomeDataHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        asyncContext.getResponse().getWriter().write("foobar");
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerExplicitFlush(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        ExplicitFlushHandler handler = new ExplicitFlushHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerExplicitFlushAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        ExplicitFlushHandler handler = new ExplicitFlushHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        if (inWait)
        {
            // throw happens before flush
            assertThat(response.getStatus(), is(500));
        }
        else
        {
            // flush happens before throw
            assertThat(response.getStatus(), is(200));
            if (httpVersion.is("HTTP/1.1"))
                assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
        }
        assertThat(handler.failure(), is(nullValue()));
    }

    private class ExplicitFlushHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private ExplicitFlushHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.getWriter().write("foobar");
                        asyncContextResponse.flushBuffer();
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandledAndFlushWithoutContent(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetHandledAndFlushWithoutContentHandler handler = new SetHandledAndFlushWithoutContentHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandledAndFlushWithoutContentAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetHandledAndFlushWithoutContentHandler handler = new SetHandledAndFlushWithoutContentHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        if (inWait)
        {
            // throw happens before async behaviour, so is handled
            assertThat(response.getStatus(), is(500));
        }
        else
        {
            assertThat(response.getStatus(), is(200));
            if (httpVersion.is("HTTP/1.1"))
                assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
        }

        assertThat(handler.failure(), is(nullValue()));
    }

    private class SetHandledAndFlushWithoutContentHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private SetHandledAndFlushWithoutContentHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        asyncContext.getResponse().flushBuffer();
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteFlushWriteMore(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        WriteFlushWriteMoreHandler handler = new WriteFlushWriteMoreHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(handler.failure(), is(nullValue()));

        // HTTP/1.0 does not do chunked.  it will just send content and close
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteFlushWriteMoreAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        WriteFlushWriteMoreHandler handler = new WriteFlushWriteMoreHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        if (inWait)
        {
            // The exception is thrown before we do any writing or async operations, so it delivered as onError and then
            // dispatched.
            assertThat(response.getStatus(), is(500));
        }
        else
        {
            assertThat(response.getStatus(), is(200));
            if (httpVersion.is("HTTP/1.1"))
                assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
        }
        assertThat(handler.failure(), is(nullValue()));
    }

    private class WriteFlushWriteMoreHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private WriteFlushWriteMoreHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.getWriter().write("foo");
                        asyncContextResponse.flushBuffer();
                        asyncContextResponse.getWriter().write("bar");
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testBufferOverflow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        OverflowHandler handler = new OverflowHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
        assertThat(handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testBufferOverflowAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        OverflowHandler handler = new OverflowHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Buffer size smaller than content, so writing will commit response.
        // If this happens before the exception is thrown we get a 200, else a 500 is produced
        if (inWait)
        {
            assertThat(response.getStatus(), is(500));
            assertThat(response.getContent(), containsString("TestCommitException: Thrown by test"));
        }
        else
        {
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("foobar"));
            if (httpVersion.is("HTTP/1.1"))
                assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
            assertThat(handler.failure(), is(nullValue()));
        }
    }

    private class OverflowHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private OverflowHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.setBufferSize(3);
                        asyncContextResponse.getWriter().write("foobar");
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytes(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetContentLengthAndWriteThatAmountOfBytesHandler handler = new SetContentLengthAndWriteThatAmountOfBytesHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        assertThat(handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetContentLengthAndWriteThatAmountOfBytesHandler handler = new SetContentLengthAndWriteThatAmountOfBytesHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        if (inWait)
        {
            // too late!
            assertThat(response.getStatus(), is(500));
        }
        else
        {
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("foo"));
            assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        }
        assertThat(handler.failure(), is(nullValue()));
    }

    private class SetContentLengthAndWriteThatAmountOfBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private SetContentLengthAndWriteThatAmountOfBytesHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.setContentLength(3);
                        asyncContextResponse.getWriter().write("foo");
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteMoreBytes(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetContentLengthAndWriteMoreBytesHandler handler = new SetContentLengthAndWriteMoreBytesHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        // jetty truncates the body when content-length is reached.! This is correct and desired behaviour?
        assertThat(response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        assertThat(handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteMoreAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        SetContentLengthAndWriteMoreBytesHandler handler = new SetContentLengthAndWriteMoreBytesHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        if (inWait)
        {
            // too late!
            assertThat(response.getStatus(), is(500));
        }
        else
        {
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("foo"));
            assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        }
        assertThat(handler.failure(), is(nullValue()));
    }

    private class SetContentLengthAndWriteMoreBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private SetContentLengthAndWriteMoreBytesHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.setContentLength(3);
                        asyncContextResponse.getWriter().write("foobar");
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLength(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        WriteAndSetContentLengthHandler handler = new WriteAndSetContentLengthHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat(response.getStatus(), is(200));
        assertThat(handler.failure(), is(nullValue()));
        //TODO: jetty ignores setContentLength and sends transfer-encoding header. Correct?
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLengthAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        WriteAndSetContentLengthHandler handler = new WriteAndSetContentLengthHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);
        if (inWait)
        {
            // too late
            assertThat(response.getStatus(), is(500));
        }
        else
        {
            assertThat(response.getStatus(), is(200));
        }
        assertThat(handler.failure(), is(nullValue()));
    }

    private class WriteAndSetContentLengthHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private WriteAndSetContentLengthHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.getWriter().write("foo");
                        asyncContextResponse.setContentLength(3); // This should commit the response
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                    catch (IOException e)
                    {
                        markFailed(e);
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLengthTooSmall(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        WriteAndSetContentLengthTooSmallHandler handler = new WriteAndSetContentLengthTooSmallHandler(false, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting a content-length too small throws an IllegalStateException,
        // but only in the async handler, which completes or dispatches anyway
        assertThat(response.getStatus(), is(200));
        assertThat(handler.failure(), not(is(nullValue())));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLengthTooSmallAndThrow(HttpVersion httpVersion, boolean dispatch, boolean inWait) throws Exception
    {
        WriteAndSetContentLengthTooSmallHandler handler = new WriteAndSetContentLengthTooSmallHandler(true, dispatch, inWait);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response;
        try (StacklessLogging stackless = new StacklessLogging(HttpChannelState.class))
        {
            response = executeRequest(httpVersion);
        }

        assertThat(response.getStatus(), is(500));

        if (!inWait)
            assertThat(handler.failure(), not(is(nullValue())));
        else
            assertThat(handler.failure(), is(nullValue()));
    }

    private class WriteAndSetContentLengthTooSmallHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;
        private final boolean inWait;

        private WriteAndSetContentLengthTooSmallHandler(boolean throwException, boolean dispatch, boolean inWait)
        {
            super(throwException);
            this.dispatch = dispatch;
            this.inWait = inWait;
        }

        @Override
        public void handle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(contextAttribute) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(contextAttribute, asyncContext);
                runAsync(baseRequest, inWait, () ->
                {
                    try
                    {
                        ServletResponse asyncContextResponse = asyncContext.getResponse();
                        asyncContextResponse.getWriter().write("foobar");
                        asyncContextResponse.setContentLength(3);
                    }
                    catch (Throwable e)
                    {
                        markFailed(e);
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                });
            }
            baseRequest.setHandled(true);
            super.handle(target, baseRequest, request, response);
        }
    }

    private void runAsyncInAsyncWait(Request request, Runnable task)
    {
        server.getThreadPool().execute(() ->
        {
            long start = NanoTime.now();
            try
            {
                while (NanoTime.secondsSince(start) < 10)
                {
                    switch (request.getHttpChannelState().getState())
                    {
                        case WAITING:
                            task.run();
                            return;

                        case HANDLING:
                            Thread.sleep(100);
                            continue;

                        default:
                            request.getHttpChannel().abort(new IllegalStateException());
                            return;
                    }
                }
                request.getHttpChannel().abort(new TimeoutException());
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        });
    }

    private void runAsyncWhileDispatched(Runnable task)
    {
        CountDownLatch ran = new CountDownLatch(1);

        server.getThreadPool().execute(() ->
        {
            try
            {
                task.run();
            }
            finally
            {
                ran.countDown();
            }
        });

        try
        {
            ran.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    private void runAsync(Request request, boolean inWait, Runnable task)
    {
        if (inWait)
            runAsyncInAsyncWait(request, task);
        else
            runAsyncWhileDispatched(task);
    }
}
