//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import org.eclipse.jetty.http.MetaData;

// TODO this should be an interface
public class RequestLog
{
    public void log(Request wrapper, MetaData.Request request, MetaData.Response responseMeta)
    {
        // TODO is this untyped method for getting get wrapped attributes workable? efficient? fragile?
        Object bytesRead = wrapper.getAttribute("o.e.j.s.h.StatsHandler.bytesRead");
        Object bytesWritten = wrapper.getAttribute("o.e.j.s.h.StatsHandler.bytesWritten");
        Object contextPath = wrapper.getAttribute("o.e.j.s.h.ScopedRequest.contextPath");
        Object servlet = wrapper.getAttribute("o.e.j.s.s.ServletScopedRequest.servlet");

        // If logging of a specific servlet API is needed, these can be downcast
        Object servletRequest = wrapper.getAttribute("o.e.j.s.s.ServletScopedRequest.request");
        Object servletResponse = wrapper.getAttribute("o.e.j.s.s.ServletScopedRequest.response");
    }
}
