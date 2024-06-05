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

package org.eclipse.jetty.tests.ccd.ee8;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.tests.ccd.common.DispatchPlan;

public class DumpServlet extends HttpServlet
{
    private static final String NULL = "<null>";

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
    {
        DispatchPlan dispatchPlan = (DispatchPlan)req.getAttribute(DispatchPlan.class.getName());

        if (dispatchPlan == null)
            throw new ServletException("Unable to find DispatchPlan");

        dispatchPlan.addEvent("%s.service() dispatcherType=%s method=%s requestUri=%s",
            this.getClass().getName(),
            req.getDispatcherType(), req.getMethod(), req.getRequestURI());

        Properties props = new Properties();
        props.setProperty("request.authType", Objects.toString(req.getAuthType(), NULL));
        props.setProperty("request.characterEncoding", Objects.toString(req.getCharacterEncoding(), NULL));
        props.setProperty("request.contentLength", Long.toString(req.getContentLengthLong()));
        props.setProperty("request.contentType", Objects.toString(req.getContentType(), NULL));
        props.setProperty("request.contextPath", Objects.toString(req.getContextPath(), NULL));
        props.setProperty("request.dispatcherType", Objects.toString(req.getDispatcherType(), NULL));
        props.setProperty("request.localAddr", Objects.toString(req.getLocalAddr(), NULL));
        props.setProperty("request.localName", Objects.toString(req.getLocalName(), NULL));
        props.setProperty("request.localPort", Integer.toString(req.getLocalPort()));
        props.setProperty("request.locale", Objects.toString(req.getLocale(), NULL));
        props.setProperty("request.method", Objects.toString(req.getMethod(), NULL));
        props.setProperty("request.pathInfo", Objects.toString(req.getPathInfo(), NULL));
        props.setProperty("request.pathTranslated", Objects.toString(req.getPathTranslated(), NULL));
        props.setProperty("request.protocol", Objects.toString(req.getProtocol(), NULL));
        props.setProperty("request.queryString", Objects.toString(req.getQueryString(), NULL));
        props.setProperty("request.remoteAddr", Objects.toString(req.getRemoteAddr(), NULL));
        props.setProperty("request.remoteHost", Objects.toString(req.getRemoteHost(), NULL));
        props.setProperty("request.remotePort", Integer.toString(req.getRemotePort()));
        props.setProperty("request.remoteUser", Objects.toString(req.getRemoteUser(), NULL));
        props.setProperty("request.requestedSessionId", Objects.toString(req.getRequestedSessionId(), NULL));
        props.setProperty("request.requestURI", Objects.toString(req.getRequestURI(), NULL));
        props.setProperty("request.requestURL", Objects.toString(req.getRequestURL(), NULL));
        props.setProperty("request.serverPort", Integer.toString(req.getServerPort()));
        props.setProperty("request.servletPath", Objects.toString(req.getServletPath(), NULL));

        List<String> attrNames = Collections.list(req.getAttributeNames());
        attrNames
            // .stream().filter((name) -> name.startsWith("javax.") || name.startsWith("jakarta."))
            .forEach((name) ->
            {
                Object attrVal = req.getAttribute(name);
                props.setProperty("attr[" + name + "]", Objects.toString(attrVal, NULL));
            });

        List<String> headerNames = Collections.list(req.getHeaderNames());
        headerNames
            .forEach((name) ->
            {
                String headerVal = req.getHeader(name);
                props.setProperty("header[" + name + "]", Objects.toString(headerVal, NULL));
            });

        int eventCount = dispatchPlan.getEvents().size();
        props.setProperty("dispatchPlan.events.count", Integer.toString(dispatchPlan.getEvents().size()));
        for (int i = 0; i < eventCount; i++)
        {
            props.setProperty("dispatchPlan.event[" + i + "]", dispatchPlan.getEvents().get(i));
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("text/x-java-properties");
        PrintWriter out = resp.getWriter();
        props.store(out, "From " + this.getClass().getName());
    }
}
