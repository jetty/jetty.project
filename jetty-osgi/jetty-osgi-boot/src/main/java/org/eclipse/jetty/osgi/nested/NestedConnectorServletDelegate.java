//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.nested;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.nested.NestedConnector;

/**
 * Wraps a NestedConnector into a servlet that can be plugged into
 * BridgeServlet#registerServletDelegate
 */
public class NestedConnectorServletDelegate extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    private final NestedConnector _nestedConnector;

    public NestedConnectorServletDelegate(NestedConnector nestedConnector)
    {
        _nestedConnector = nestedConnector;
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        _nestedConnector.service(req, res);
    }

}
