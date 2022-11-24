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
    public abstract Processor matchAndApply(Processor input) throws IOException;

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
     * <p>A {@link Request.Wrapper} that is also a {@link org.eclipse.jetty.server.Request.Processor},
     * used to chain a sequence of {@link Rule}s together.
     * The tuple is initialized with only the request, then the processor is
     * then passed to a chain of rules before the ultimate processor is
     * passed in {@link #wrapProcessor(Processor)}. Finally, the response
     * and callback are provided in a call to {@link #process(Request, Response, Callback)},
     * which calls the {@link #process(Response, Callback)}.</p>
     */
    public static class Processor extends Request.Wrapper implements Request.Processor
    {
        private volatile Processor _processor;

        public Processor(Request request)
        {
            super(request);
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            assert getWrapped() == request;
            process(response, callback);
        }

        /**
         * <p>Processes this wrapped request together with the passed response and
         * callback, using the processor set in {@link #wrapProcessor(Processor)}.
         * This method should be extended if additional processing of the wrapped
         * request is required.</p>
         * @param response The response
         * @param callback The callback
         * @throws Exception If there is a problem processing
         * @see #wrapProcessor(Processor)
         */
        protected void process(Response response, Callback callback) throws Exception
        {
            Processor processor = _processor;
            if (processor != null)
                processor.process(this, response, callback);
        }

        /**
         * <p>Wraps the given {@code Processor} within this instance and returns this instance.</p>
         *
         * @param processor the {@code Processor} to wrap
         * @return this instance
         */
        public Processor wrapProcessor(Processor processor)
        {
            _processor = processor;
            return processor == null ? null : this::processWithWrappers;
        }

        protected void processWithWrappers(Request ignored, Response response, Callback callback) throws Exception
        {
            process(response, callback);
        }
    }

    public static class HttpURIProcessor extends Processor
    {
        private final HttpURI _uri;

        public HttpURIProcessor(Request request, HttpURI uri)
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
