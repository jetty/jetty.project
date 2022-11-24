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
    public abstract RuleProcessor matchAndApply(RuleProcessor input) throws IOException;

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
     * A tuple of a {@link Request.Wrapper} and is also a {@link org.eclipse.jetty.server.Request.Processor},
     * that is used to chain a sequence of {@link Rule}s together.
     * The tuple is initialized with only the request, then the processor is
     * then passed to a chain of rules before the ultimate processor is
     * passed in {@link #wrapProcessor(Processor)}. Finally, the response
     * and callback are provided in a call to {@link #process(Request, Response, Callback)},
     * which calls the {@link #process(Response, Callback)}.
     */
    public static class RuleProcessor extends Request.Wrapper implements Request.Processor
    {
        private volatile Processor _processor;

        public RuleProcessor(Request request)
        {
            super(request);
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            assert getWrapped() == request;
            process(response, callback);
        }

        /** Process this wrapped request together with the passed response and
         * callback, using the processor set in {@link #wrapProcessor(Processor)}.
         * This method should be extended if additional processing of the wrapped
         * request is required.
         * @param response The response
         * @param callback The callback
         * @throws Exception If there is a problem processing
         * @see #wrapProcessor(Processor).
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
            return processor == null ? null : this::wrappedProcess;
        }

        protected Request wrapRequest(Request request)
        {
            return request;
        }

        protected Response wrapResponse(Request request, Response response)
        {
            return response;
        }

        private void wrappedProcess(Request request, Response response, Callback callback) throws Exception
        {
            Request wrapped = this.getWrapped();
            while (wrapped != null)
            {
                if (request == wrapped)
                {
                    process(wrapResponse(this, response), callback);
                    return;
                }
                wrapped = wrapped instanceof Request.Wrapper w ? w.getWrapped() : null;
            }

            // We need a new instance of the wrapper
            Request wrapper = wrapRequest(request);
            process(wrapResponse(wrapper, response), callback);
        }
    }

    public static class UriRuleProcessor extends RuleProcessor
    {
        private final HttpURI _uri;

        public UriRuleProcessor(Request request, HttpURI uri)
        {
            super(request);
            _uri = uri;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }

        @Override
        protected Request wrapRequest(Request request)
        {
            return new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return _uri;
                }
            };
        }
    }
}
