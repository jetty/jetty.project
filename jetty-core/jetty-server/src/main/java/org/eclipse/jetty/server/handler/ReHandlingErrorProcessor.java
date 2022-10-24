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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An ErrorProcessor that can re-handle a request at an error page location.
 */
public abstract class ReHandlingErrorProcessor extends ErrorProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(ReHandlingErrorProcessor.class);

    private final Handler _handler;

    protected ReHandlingErrorProcessor(Handler handler)
    {
        _handler = handler;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return _handler.getInvocationType();
    }

    @Override
    protected void generateResponse(Request request, Response response, int code, String message, Throwable cause, Callback callback) throws IOException
    {
        if (request.getAttribute(ReHandlingErrorProcessor.class.getName()) == null)
        {
            String pathInContext = getReHandlePathInContext(request, code, cause);

            if (pathInContext != null)
            {
                request.setAttribute(ReHandlingErrorProcessor.class.getName(), pathInContext);
                HttpURI uri = HttpURI.build(request.getHttpURI()).path(URIUtil.addPaths(request.getContext().getContextPath(), pathInContext)).asImmutable();
                Request.Wrapper wrapper = new ReHandleRequestWrapper(request, uri, uri.getCanonicalPath());

                try
                {
                    Request.Processor processor = _handler.handle(wrapper);
                    if (processor != null)
                    {
                        response.setStatus(200);
                        processor.process(wrapper, response, callback);
                        return;
                    }
                }
                catch (Exception e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to process error {}", wrapper, e);
                    if (cause != null && ExceptionUtil.areNotAssociated(cause, e))
                        cause.addSuppressed(e);
                    response.setStatus(code);
                }
            }
        }
        super.generateResponse(request, response, code, message, cause, callback);
    }

    protected abstract String getReHandlePathInContext(Request request, int code, Throwable cause);

    /**
     * An ErrorPageErrorProcessor that uses a map of error codes to select a page.
     */
    public static class ByHttpStatus extends ReHandlingErrorProcessor
    {
        private final Map<Integer, String> _statusMap = new ConcurrentHashMap<>();

        public ByHttpStatus(Handler handler)
        {
            super(handler);
        }

        @Override
        protected String getReHandlePathInContext(Request request, int code, Throwable cause)
        {
            return get(code);
        }

        public String put(int code, String pathInContext)
        {
            return _statusMap.put(code, pathInContext);
        }

        public String get(int code)
        {
            return _statusMap.get(code);
        }

        public String remove(int code)
        {
            return _statusMap.remove(code);
        }
    }

    private static class ReHandleRequestWrapper extends Request.Wrapper
    {
        private final HttpURI _uri;
        private final String _pathInContext;

        public ReHandleRequestWrapper(Request request, HttpURI uri, String pathInContext)
        {
            super(request);
            _uri = uri;
            _pathInContext = pathInContext;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }

        @Override
        public String getPathInContext()
        {
            return _pathInContext;
        }
    }
}
