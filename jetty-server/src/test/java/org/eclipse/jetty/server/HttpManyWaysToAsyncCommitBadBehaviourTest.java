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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

//TODO: reset buffer tests
//TODO: add protocol specific tests for connection: close and/or chunking

public class HttpManyWaysToAsyncCommitBadBehaviourTest extends AbstractHttpTest
{
    private final String CONTEXT_ATTRIBUTE = getClass().getName() + ".asyncContext";

    public static Stream<Arguments> httpVersions()
    {
        // boolean dispatch - if true we dispatch, otherwise we complete
        final boolean DISPATCH = true;
        final boolean COMPLETE = false;

        List<Arguments> ret = new ArrayList<>();
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, DISPATCH));
        ret.add(Arguments.of(HttpVersion.HTTP_1_0, COMPLETE));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, DISPATCH));
        ret.add(Arguments.of(HttpVersion.HTTP_1_1, COMPLETE));
        return ret.stream();
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testHandlerSetsHandledAndWritesSomeContent(HttpVersion httpVersion, boolean dispatch) throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(false, dispatch));
        server.start();

        HttpTester.Response response = executeRequest(httpVersion);

        assertThat("response code is 500", response.getStatus(), is(500));
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
            final CyclicBarrier resumeBarrier = new CyclicBarrier(1);
            
            if (baseRequest.getDispatcherType()==DispatcherType.ERROR)
            {
                response.sendError(500);
                return;
            }
            
            if (request.getAttribute(CONTEXT_ATTRIBUTE) == null)
            {
                final AsyncContext asyncContext = baseRequest.startAsync();
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
                            resumeBarrier.await(5, TimeUnit.SECONDS);
                        }
                        catch (IOException | TimeoutException | InterruptedException | BrokenBarrierException e)
                        {
                            e.printStackTrace();
                        }
                    }
                }).run();
            }
            try
            {
                resumeBarrier.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException | BrokenBarrierException | TimeoutException e)
            {
                e.printStackTrace();
            }
            throw new TestCommitException();
        }
    }
}
