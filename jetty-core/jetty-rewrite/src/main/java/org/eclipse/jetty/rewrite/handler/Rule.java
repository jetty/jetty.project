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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * <p>An abstract rule that, upon matching a certain condition, may wrap
 * the {@code Request} or the {@code Handler} to execute custom logic.</p>
 */
public abstract class Rule
{
    private boolean _terminating;

    /**
     * <p>Tests whether the given input {@code Handler} (which wraps a
     * {@code Request}) matches the rule, and if so returns an output
     * {@code Handler} that applies the rule logic.</p>
     * <p>If the input does not match, {@code null} is returned.</p>
     *
     * @param input the input {@code Handler} that wraps the {@code Request}
     * @return an output {@code Handler} that wraps the input {@code Handler},
     * or {@code null} if the rule does not match
     * @throws IOException if applying the rule fails
     */
    public abstract Handler matchAndApply(Handler input) throws IOException;

    /**
     * @return whether rules after this one are not invoked
     */
    public boolean isTerminating()
    {
        return _terminating;
    }

    /**
     * @param value whether rules after this one are not invoked
     */
    public void setTerminating(boolean value)
    {
        _terminating = value;
    }

    @Override
    public String toString()
    {
        return "%s@%x[terminating=%b]".formatted(getClass().getSimpleName(), hashCode(), isTerminating());
    }

    /**
     * <p>A {@link Request.Wrapper} used to chain a sequence of {@link Rule}s together.</p>
     * <p>The first {@link Rule.Handler} is initialized with the initial {@link Request},
     * then it is passed to a chain of {@link Rule}s, which in turn chain {@link Rule.Handler}s
     * together.
     * At the end of the {@link Rule} applications, {@link Rule.Handler}s are chained so that
     * so that the first rule produces the innermost {@code Handler} and the last rule produces
     * the outermost {@code Handler} in this way: {@code RH3(RH2(RH1(Req)))}.</p>
     * <p>After the {@link Rule} applications, the {@link Rule.Handler}s are then called in
     * sequence, starting from the innermost and moving outwards with respect to the wrapping,
     * until finally the {@link org.eclipse.jetty.server.Handler#handle(Request, Response, Callback)}
     * method of the child {@code Handler} of {@link RewriteHandler} is invoked.</p>
     */
    public static class Handler extends Request.Wrapper
    {
        private Rule.Handler _wrapper;

        protected Handler(Request request)
        {
            super(request);
        }

        public Handler(Rule.Handler handler)
        {
            super(handler);
            handler._wrapper = this;
        }

        /**
         * <p>Handles this wrapped request together with the passed response and callback.</p>
         * <p>This method should be overridden only if the rule applies to the response,
         * or the rule completes the callback.
         * By default this method forwards the handling to the next rule.
         * If a rule that overrides this method is non-{@link #isTerminating() terminating},
         * it should call the {@code super} implementation to chain the rules.</p>
         *
         * @param response the {@link Response}
         * @param callback the {@link Callback}
         * @throws Exception if there is a failure while handling the rules
         */
        protected boolean handle(Response response, Callback callback) throws Exception
        {
            return _wrapper.handle(response, callback);
        }
    }

    /**
     * <p>A {@link Rule.Handler} that wraps a {@link Request} to return a different {@link HttpURI}.</p>
     */
    public static class HttpURIHandler extends Handler
    {
        private final HttpURI _uri;

        public HttpURIHandler(Rule.Handler handler, HttpURI uri)
        {
            super(handler);
            _uri = uri;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }
    }
}
