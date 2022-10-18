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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An ErrorProcessor that can re-handle a request at an error page location.
 */
public abstract class ErrorPageErrorProcessor extends ErrorProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(ErrorPageErrorProcessor.class);

    private final Handler _handler;

    protected ErrorPageErrorProcessor(Handler handler)
    {
        _handler = handler;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return _handler.getInvocationType();
    }

    @Override
    protected void generateAcceptableResponse(Request request, Response response, int code, String message, Throwable cause, Callback callback) throws IOException
    {
        String pathInContext = getErrorPathInContext(request, code, cause);

        if (pathInContext != null)
        {
            HttpURI uri = HttpURI.build(request.getHttpURI()).path(URIUtil.addPaths(request.getContext().getContextPath(), pathInContext)).asImmutable();
            Request.Wrapper wrapper = new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return uri;
                }

                @Override
                public String getPathInContext()
                {
                    return pathInContext;
                }
            };

            try
            {
                Request.Processor processor = _handler.handle(wrapper);
                if (processor != null)
                {
                    processor.process(wrapper, response, callback);
                    return;
                }
            }
            catch (Exception e)
            {
                LOG.warn("Unable to process error {}", wrapper, e);
            }
        }

        super.generateAcceptableResponse(request, response, code, message, cause, callback);
    }

    protected abstract String getErrorPathInContext(Request request, int code, Throwable cause);

    /**
     * An ErrorPageErrorProcessor that uses a map of error codes to select a page.
     */
    public static class Mapped extends ErrorPageErrorProcessor
    {
        private final Map<Integer, String> _pageMap = new HashMap<>();

        protected Mapped(Handler handler)
        {
            super(handler);
        }

        @Override
        protected String getErrorPathInContext(Request request, int code, Throwable cause)
        {
            return _pageMap.get(code);
        }

        public String addPage(int code, String pathInContext)
        {
            return _pageMap.put(code, pathInContext);
        }
    }
}
