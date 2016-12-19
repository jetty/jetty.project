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
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
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
public class HttpManyWaysToAsyncCommitBadBehaviourTest extends AbstractHttpTest
{
    private final String CONTEXT_ATTRIBUTE = getClass().getName() + ".asyncContext";
    private boolean dispatch; // if true we dispatch, otherwise we complete

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        Object[][] data = new Object[][]{{HttpVersion.HTTP_1_0.asString(), true}, {HttpVersion.HTTP_1_0.asString(),
                false}, {HttpVersion.HTTP_1_1.asString(), true}, {HttpVersion.HTTP_1_1.asString(), false}};
        return Arrays.asList(data);
    }

    public HttpManyWaysToAsyncCommitBadBehaviourTest(String httpVersion, boolean dispatch)
    {
        super(httpVersion);
        this.httpVersion = httpVersion;
        this.dispatch = dispatch;
    }

    @Test
    public void testHandlerSetsHandledAndWritesSomeContent() throws Exception
    {
        server.setHandler(new SetHandledWriteSomeDataHandler(false));
        server.start();

        SimpleHttpResponse response = executeRequest();

        assertThat("response code is 500", response.getCode(), is("500"));
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
