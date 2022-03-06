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

package org.eclipse.jetty.ee10.servlet;

import javax.management.relation.RoleInfo;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class SecurityHandler extends Handler.Wrapper
{
    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        /*
        TODO: can't
        ServletScopedRequest.MutableHttpServletRequest servletRequest = Request.get(request, ServletScopedRequest.class, ServletScopedRequest::getMutableHttpServletRequest);
        if (servletRequest == null)
            return null;

        // if we match some security constraint, we can respond here
        if (servletRequest.getServletPath().startsWith("/secret"))
        {
            return (req, resp, cb) -> Response.writeError(req, resp, cb, 403, "secret");
        }
         */

        return super.handle(request);
    }

    protected RoleInfo prepareConstraintInfo(String pathInContext, Request request)
    {
        return null;
    }

    protected boolean checkUserDataPermissions(String pathInContext, Request request, Response response, RoleInfo constraintInfo)
    {
        return false;
    }

    protected boolean isAuthMandatory(Request baseRequest, Response baseResponse, Object constraintInfo)
    {
        return false;
    }

    protected boolean checkWebResourcePermissions(String pathInContext, Request request, Response response, Object constraintInfo, UserIdentity userIdentity)
    {
        return false;
    }
}
