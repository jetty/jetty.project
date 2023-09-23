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
     * <p>A {@link Request.Wrapper} that is also a {@link Handler},
     * used to chain a sequence of {@link Rule}s together.</p>
     * <p>The rule handler is initialized with the initial request,
     * then it is passed to a chain of rules, which in turn chain
     * rule handlers together. At the end of the rule applications,
     * the last rule handler (which eventually wraps the initial
     * request), the response and the callback are passed to the
     * chain of rule handlers and finally to the child Handler
     * of {@link RewriteHandler}.</p>
     * <p>{@code Handler}s are chained as rules are applied,
     * so that the first rule produces the innermost {@code Handler}
     * and the last rule produces the outermost {@code Handler}.</p>
     */
    public static class Handler extends Request.Wrapper implements Request.Handler
    {
        public Handler(Request request)
        {
            super(request);
        }

        /**
         * <p>Handles this wrapped request together with the passed response and callback.</p>
         * <p>This method should be overridden only if the rule applies to the response,
         * or the rule completes the callback.</p>
         * <p>By default this method for wards the handling to the next rule.</p>
         * <p>Note that the {@code request} parameter and {@code "this"} are both wrappers
         * of the initial request, but at different stages of the application of the rules.
         * The {@code request} parameter is the outermost {@code Handler}, result of the
         * application of all the rules.
         * On the other hand, {@code "this"} is the {@code Handler} result of the application
         * of the rules up to the rule that produced this {@code Handler}, and therefore
         * represents only a partial application of the rules.</p>
         *
         * @param request the outermost {@code Handler} that eventually wraps the initial {@link Request}
         * @param response the {@link Response}
         * @param callback the {@link Callback}
         * @throws Exception if there is a failure while handling the rules
         */
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Rule.Handler handler = (Rule.Handler)getWrapped();
            return handler.handle(request, response, callback);
        }
    }

    /**
     * <p>A {@link Rule.Handler} that wraps a {@link Request} to return a different {@link HttpURI}.</p>
     */
    public static class HttpURIHandler extends Handler
    {
        private final HttpURI _uri;

        public HttpURIHandler(Request request, HttpURI uri)
        {
            super(request);
            _uri = uri;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }
    }
}
