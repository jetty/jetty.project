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

import java.util.List;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>Inspired by nginx's {@code try_files} functionality.</p>
 * <p> This handler can be configured with a list of URI paths.
 * The special token {@code $path} represents the current request URI
 * path (the portion after the context path).</p>
 * <p>Typical example of how this handler can be configured is the following:</p>
 * <pre>{@code
 * TryPathsHandler tryPaths = new TryPathsHandler();
 * tryPaths.setPaths("/maintenance.html", "$path", "/index.php?p=$path");
 * }</pre>
 * <p>For a request such as {@code /context/path/to/resource.ext}, this
 * handler will try to serve the {@code /maintenance.html} file if it finds
 * it; failing that, it will try to serve the {@code /path/to/resource.ext}
 * file if it finds it; failing that it will forward the request to
 * {@code /index.php?p=/path/to/resource.ext} to the next handler.</p>
 * <p>The last URI path specified in the list is therefore the "fallback" to
 * which the request is forwarded to in case no previous files can be found.</p>
 * <p>The file paths are resolved against {@link Context#getBaseResource()}
 * to make sure that only files visible to the application are served.</p>
 */
public class TryPathsHandler extends Handler.Wrapper
{
    private List<String> paths;

    public void setPaths(List<String> paths)
    {
        this.paths = paths;
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        String interpolated = interpolate(request, "$path");
        Resource rootResource = request.getContext().getBaseResource();
        if (rootResource != null)
        {
            for (String path : paths)
            {
                interpolated = interpolate(request, path);
                Resource resource = rootResource.resolve(interpolated);
                if (resource != null && resource.exists())
                    break;
            }
        }
        Request.WrapperProcessor result = new Request.WrapperProcessor(new TryPathsRequest(request, interpolated));
        return result.wrapProcessor(super.handle(result));
    }

    private Request.Processor fallback(Request request) throws Exception
    {
        String fallback = paths.isEmpty() ? "$path" : paths.get(paths.size() - 1);
        String interpolated = interpolate(request, fallback);
        return super.handle(new TryPathsRequest(request, interpolated));
    }

    private String interpolate(Request request, String value)
    {
        String path = Request.getPathInContext(request);
        return value.replace("$path", path);
    }

    private static class TryPathsRequest extends Request.Wrapper
    {
        private final HttpURI _uri;

        public TryPathsRequest(Request wrapped, String interpolated)
        {
            super(wrapped);
            int queryIdx = interpolated.indexOf('?');
            if (queryIdx >= 0)
            {
                String path = interpolated.substring(0, queryIdx);
                _uri = HttpURI.build(wrapped.getHttpURI())
                    .path(URIUtil.addPaths(Request.getContextPath(wrapped), path))
                    .query(interpolated.substring(queryIdx+1))
                    .asImmutable();
            }
            else
            {
                _uri = Request.newHttpURIFrom(wrapped, URIUtil.canonicalPath(interpolated));
            }
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }
    }
}
