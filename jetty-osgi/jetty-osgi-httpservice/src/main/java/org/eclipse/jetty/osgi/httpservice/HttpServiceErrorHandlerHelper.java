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

package org.eclipse.jetty.osgi.httpservice;

import javax.servlet.http.HttpServlet;

/**
 * For jetty agnostic handling of errors issued by the HttpService.
 * Pass a servlet to the method setHttpServiceErrorHandler.
 * In the servlet to read the status code of the error or the message or the exception,
 * use org.eclipse.jetty.server.Dispatch's constants:
 * int errorCode = httpServletRequest.getAttribute(Dispatcher.ERROR_STATUS_CODE)
 * for example.
 */
public class HttpServiceErrorHandlerHelper
{
    private static HttpServlet _customErrorHandler;

    public static HttpServlet getCustomErrorHandler()
    {
        return _customErrorHandler;
    }

    public static void setHttpServiceErrorHandler(HttpServlet servlet)
    {
        _customErrorHandler = servlet;
    }
}
