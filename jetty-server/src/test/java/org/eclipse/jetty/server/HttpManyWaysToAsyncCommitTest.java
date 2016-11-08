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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking
@RunWith(value = Parameterized.class)
public class HttpManyWaysToAsyncCommitTest extends AbstractHttpTest
{
    private final String CONTEXT_ATTRIBUTE = getClass().getName() + ".asyncContext";
    private boolean dispatch; // if true we dispatch, otherwise we complete

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]
            {
            {HttpVersion.HTTP_1_0.asString(), true}, 
            {HttpVersion.HTTP_1_1.asString(), true}, 
            {HttpVersion.HTTP_1_0.asString(), false}, 
            {HttpVersion.HTTP_1_1.asString(), false}
            };
        return Arrays.asList(data);
    }

    public HttpManyWaysToAsyncCommitTest(String httpVersion, boolean dispatch)
    {
        super(httpVersion);
        this.httpVersion = httpVersion;
        this.dispatch = dispatch;
    }

    @Test
    public void testHandlerDoesNotSetHandled() throws Exception
    {
        DoesNotSetHandledHandler handler = new DoesNotSetHandledHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 404", response.getCode(), is("404"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testHandlerDoesNotSetHandledAndThrow() throws Exception
    {
        DoesNotSetHandledHandler handler = new DoesNotSetHandledHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class DoesNotSetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private DoesNotSetHandledHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testHandlerSetsHandledTrueOnly() throws Exception
    {
        OnlySetHandledHandler handler = new OnlySetHandledHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        if (HttpVersion.HTTP_1_1.asString().equals(httpVersion))
            assertHeader(response, "content-length", "0");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testHandlerSetsHandledTrueOnlyAndThrow() throws Exception
    {
        OnlySetHandledHandler handler = new OnlySetHandledHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class OnlySetHandledHandler extends ThrowExceptionOnDemandHandler
    {
        private OnlySetHandledHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testHandlerSetsHandledAndWritesSomeContent() throws Exception
    {
        SetHandledWriteSomeDataHandler handler = new SetHandledWriteSomeDataHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertHeader(response, "content-length", "6");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContentAndThrow() throws Exception
    {
        SetHandledWriteSomeDataHandler handler = new SetHandledWriteSomeDataHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class SetHandledWriteSomeDataHandler extends ThrowExceptionOnDemandHandler
    {
        private SetHandledWriteSomeDataHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testHandlerExplicitFlush() throws Exception
    {
        ExplicitFlushHandler handler = new ExplicitFlushHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();


        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandlerExplicitFlushAndThrow() throws Exception
    {
        ExplicitFlushHandler handler = new ExplicitFlushHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
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

    @Test
    public void testHandledAndFlushWithoutContent() throws Exception
    {
        SetHandledAndFlushWithoutContentHandler handler = new SetHandledAndFlushWithoutContentHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
    }

    @Test
    public void testHandledAndFlushWithoutContentAndThrow() throws Exception
    {
        SetHandledAndFlushWithoutContentHandler handler = new SetHandledAndFlushWithoutContentHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
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

    @Test
    public void testWriteFlushWriteMore() throws Exception
    {
        WriteFlushWriteMoreHandler handler = new WriteFlushWriteMoreHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked"); // HTTP/1.0 does not do chunked.  it will just send content and close
    }

    @Test
    public void testWriteFlushWriteMoreAndThrow() throws Exception
    {
        WriteFlushWriteMoreHandler handler = new WriteFlushWriteMoreHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
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

    @Test
    public void testBufferOverflow() throws Exception
    {
        OverflowHandler handler = new OverflowHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testBufferOverflowAndThrow() throws Exception
    {
        OverflowHandler handler = new OverflowHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Buffer size is too small, so the content is written directly producing a 200 response
        assertThat("response code is 200", response.getCode(), is("200"));
        assertResponseBody(response, "foobar");
        if (!"HTTP/1.0".equals(httpVersion))
            assertHeader(response, "transfer-encoding", "chunked");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class OverflowHandler extends ThrowExceptionOnDemandHandler
    {
        private OverflowHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytes() throws Exception
    {
        SetContentLengthAndWriteThatAmountOfBytesHandler handler = new SetContentLengthAndWriteThatAmountOfBytesHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testSetContentLengthAndWriteExactlyThatAmountOfBytesAndThrow() throws Exception
    {
        SetContentLengthAndWriteThatAmountOfBytesHandler handler = new SetContentLengthAndWriteThatAmountOfBytesHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        //TODO: should we expect 500 here?
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class SetContentLengthAndWriteThatAmountOfBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private SetContentLengthAndWriteThatAmountOfBytesHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testSetContentLengthAndWriteMoreBytes() throws Exception
    {
        SetContentLengthAndWriteMoreBytesHandler handler = new SetContentLengthAndWriteMoreBytesHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        // jetty truncates the body when content-length is reached.! This is correct and desired behaviour?
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testSetContentLengthAndWriteMoreAndThrow() throws Exception
    {
        SetContentLengthAndWriteMoreBytesHandler handler = new SetContentLengthAndWriteMoreBytesHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        // TODO: we throw before response is committed. should we expect 500?
        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("response body is foo", response.getBody(), is("foo"));
        assertHeader(response, "content-length", "3");
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class SetContentLengthAndWriteMoreBytesHandler extends ThrowExceptionOnDemandHandler
    {
        private SetContentLengthAndWriteMoreBytesHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testWriteAndSetContentLength() throws Exception
    {
        WriteAndSetContentLengthHandler handler = new WriteAndSetContentLengthHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
        //TODO: jetty ignores setContentLength and sends transfer-encoding header. Correct?
    }

    @Test
    public void testWriteAndSetContentLengthAndThrow() throws Exception
    {
        WriteAndSetContentLengthHandler handler = new WriteAndSetContentLengthHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 200", response.getCode(), is("200"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class WriteAndSetContentLengthHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteAndSetContentLengthHandler(boolean throwException)
        {
            super(throwException);
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

    @Test
    public void testWriteAndSetContentLengthTooSmall() throws Exception
    {
        WriteAndSetContentLengthTooSmallHandler handler = new WriteAndSetContentLengthTooSmallHandler(false);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Setting a content-length too small throws an IllegalStateException
        assertThat("response code is 500", response.getCode(), is("500"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    @Test
    public void testWriteAndSetContentLengthTooSmallAndThrow() throws Exception
    {
        WriteAndSetContentLengthTooSmallHandler handler = new WriteAndSetContentLengthTooSmallHandler(true);
        server.setHandler(handler);
        server.start();

        SimpleHttpResponse response = executeRequest();

        // Setting a content-length too small throws an IllegalStateException
        assertThat(response.getCode(), is("500"));
        assertThat("no exceptions", handler.failure(), is(nullValue()));
    }

    private class WriteAndSetContentLengthTooSmallHandler extends ThrowExceptionOnDemandHandler
    {
        private WriteAndSetContentLengthTooSmallHandler(boolean throwException)
        {
            super(throwException);
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
