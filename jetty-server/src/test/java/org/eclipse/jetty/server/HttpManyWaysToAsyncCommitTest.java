//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.http.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking
public class HttpManyWaysToAsyncCommitTest extends AbstractHttpTest
{
    private final String CONTEXT_ATTRIBUTE = getClass().getName() + ".asyncContext";

    public static Stream<Arguments> httpVersion()
    {
        // boolean dispatch - if true we dispatch, otherwise we complete
        final boolean DISPATCH = true;
        final boolean COMPLETE = false;

        List<Arguments> ret = new ArrayList<>();
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, DISPATCH));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, DISPATCH));
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, COMPLETE));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, COMPLETE));
        return ret.stream();
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerDoesNotSetHandled(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        DoesNotSetHandledHandler handler = new DoesNotSetHandledHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(404));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerDoesNotSetHandledAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        DoesNotSetHandledHandler handler = new DoesNotSetHandledHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(500));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class DoesNotSetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private DoesNotSetHandledHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                }).run();
            }
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledTrueOnly(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        OnlySetHandledHandler handler = new OnlySetHandledHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);
    
        assertThat("response code", response.getStatus(), is(200));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "0"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledTrueOnlyAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        OnlySetHandledHandler handler = new OnlySetHandledHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(500));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class OnlySetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private OnlySetHandledHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (dispatch)
                            asyncContext.dispatch();
                        else
                            asyncContext.complete();
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledAndWritesSomeContent(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetHandledWriteSomeDataHandler handler = new SetHandledWriteSomeDataHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "6"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetHandledWriteSomeDataHandler handler = new SetHandledWriteSomeDataHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(500));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class SetHandledWriteSomeDataHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private SetHandledWriteSomeDataHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerExplicitFlush(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        ExplicitFlushHandler handler = new ExplicitFlushHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);


        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandlerExplicitFlushAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        ExplicitFlushHandler handler = new ExplicitFlushHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    private class ExplicitFlushHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private ExplicitFlushHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandledAndFlushWithoutContent(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetHandledAndFlushWithoutContentHandler handler = new SetHandledAndFlushWithoutContentHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testHandledAndFlushWithoutContentAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetHandledAndFlushWithoutContentHandler handler = new SetHandledAndFlushWithoutContentHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    private class SetHandledAndFlushWithoutContentHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private SetHandledAndFlushWithoutContentHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteFlushWriteMore(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        WriteFlushWriteMoreHandler handler = new WriteFlushWriteMoreHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));

        // HTTP/1.0 does not do chunked.  it will just send content and close
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteFlushWriteMoreAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        WriteFlushWriteMoreHandler handler = new WriteFlushWriteMoreHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
    }

    private class WriteFlushWriteMoreHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private WriteFlushWriteMoreHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testBufferOverflow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        OverflowHandler handler = new OverflowHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testBufferOverflowAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        OverflowHandler handler = new OverflowHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Buffer size is too small, so the content is written directly producing a 200 response
        assertThat("response code", response.getStatus(), is(200));
        assertThat(response.getContent(), is("foobar"));
        if (httpVersion.is("HTTP/1.1"))
            assertThat(response, containsHeaderValue(HttpHeader.TRANSFER_ENCODING, "chunked"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class OverflowHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private OverflowHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytes(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetContentLengthAndWriteThatAmountOfBytesHandler handler = new SetContentLengthAndWriteThatAmountOfBytesHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetContentLengthAndWriteThatAmountOfBytesHandler handler = new SetContentLengthAndWriteThatAmountOfBytesHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        //TODO: should we expect 500 here?
        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class SetContentLengthAndWriteThatAmountOfBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private SetContentLengthAndWriteThatAmountOfBytesHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteMoreBytes(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetContentLengthAndWriteMoreBytesHandler handler = new SetContentLengthAndWriteMoreBytesHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        // jetty truncates the body when content-length is reached.! This is correct and desired behaviour?
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testSetContentLengthAndWriteMoreAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        SetContentLengthAndWriteMoreBytesHandler handler = new SetContentLengthAndWriteMoreBytesHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // TODO: we throw before response is committed. should we expect 500?
        assertThat("response code", response.getStatus(), is(200));
        assertThat("response body", response.getContent(), is("foo"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "3"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class SetContentLengthAndWriteMoreBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private SetContentLengthAndWriteMoreBytesHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLength(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        WriteAndSetContentLengthHandler handler = new WriteAndSetContentLengthHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        //TODO: jetty ignores setContentLength and sends transfer-encoding header. Correct?
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLengthAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        WriteAndSetContentLengthHandler handler = new WriteAndSetContentLengthHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code", response.getStatus(), is(200));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class WriteAndSetContentLengthHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private WriteAndSetContentLengthHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLengthTooSmall(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        WriteAndSetContentLengthTooSmallHandler handler = new WriteAndSetContentLengthTooSmallHandler(false, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code", response.getStatus(), is(500));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("httpVersion")
    public void testWriteAndSetContentLengthTooSmallAndThrow(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        WriteAndSetContentLengthTooSmallHandler handler = new WriteAndSetContentLengthTooSmallHandler(true, dispatch);
        server.setHandler(handler);
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code", response.getStatus(), is(500));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class WriteAndSetContentLengthTooSmallHandler extends ThrowExceptionOnDemandHandler
    {
        private final boolean dispatch;

        private WriteAndSetContentLengthTooSmallHandler(boolean throwException, boolean dispatch)
        {
            super(throwException);
            this.dispatch = dispatch;
        }

        @Override
        public void doNonErrorHandle(String target, Request baseRequest, final HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
                request.setAttribute(CONTEXT_ATTRIBUTE, asyncContext);
                new Thread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            ServletResponse asyncContextResponse = asyncContext.getResponse();
                            asyncContextResponse.getWriter().write("foobar");
                            asyncContextResponse.setContentLength(3);
                            if (dispatch)
                                asyncContext.dispatch();
                            else
                                asyncContext.complete();
                        }
                        catch (IOException e)
                        {
                            markFailed(e);
                        }
                    }
                }).run();
            }
            baseRequest.setHandled(true);
            super.doNonErrorHandle(target, baseRequest, request, response);
        }
    }
}
