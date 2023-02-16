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
 * the {@code Request} or the {@code Processor} to execute custom logic.</p>
 */
public abstract class Rule
{
    private boolean _terminating;

    /**
     * <p>Tests whether the given {@code Request} should apply, and if so the rule logic is triggered.</p>
     *
     * @param input the input {@code Request} and {@code Processor}
     * @return the possibly wrapped {@code Request} and {@code Processor}, or {@code null} if the rule did not match
     * @throws IOException if applying the rule failed
     */
    public abstract Handler matchAndApply(Handler input) throws IOException;

    /**
     * @return when {@code true}, rules after this one are not processed
     */
    public boolean isTerminating()
    {
        return _terminating;
    }

    public void setTerminating(boolean value)
    {
        _terminating = value;
    }

    /**
     * Returns the handling and terminating flag values.
     */
    @Override
    public String toString()
    {
        return "%s@%x[terminating=%b]".formatted(getClass().getSimpleName(), hashCode(), isTerminating());
    }

    /**
     * <p>A {@link Request.Wrapper} that is also a {@link Handler},
     * used to chain a sequence of {@link Rule}s together.
     * The rule handler is initialized with the initial request, then it is
     * passed to a chain of rules before the child {@code Handler} is
     * passed in {@link #setHandler(Handler)}. Finally, the response
     * and callback are provided in a call to {@link #handle(Request, Response, Callback)},
     * which calls the {@link #handle(Response, Callback)}.</p>
     */
    public static class Handler extends Request.Wrapper implements Request.Handler
    {
        private volatile Handler _handler;

        public Handler(Request request)
        {
            super(request);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            return handle(response, callback);
        }

        /**
         * <p>Handles this wrapped request together with the passed response and
         * callback, using the handler set in {@link #setHandler(Handler)}.
         * This method should be extended if additional processing of the wrapped
         * request is required.</p>
         * @param response The response
         * @param callback The callback
         * @throws Exception If there is a problem processing
         * @see #setHandler(Handler)
         */
        protected boolean handle(Response response, Callback callback) throws Exception
        {
            Handler handler = _handler;
            return handler != null && handler.handle(this, response, callback);
        }

        /**
         * <p>Wraps the given {@code Processor} within this instance and returns this instance.</p>
         *
         * @param handler the {@code Processor} to wrap
         */
        public void setHandler(Handler handler)
        {
            _handler = handler;
        }
    }

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
