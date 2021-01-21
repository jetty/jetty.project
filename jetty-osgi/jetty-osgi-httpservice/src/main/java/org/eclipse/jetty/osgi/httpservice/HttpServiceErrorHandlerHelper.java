//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
