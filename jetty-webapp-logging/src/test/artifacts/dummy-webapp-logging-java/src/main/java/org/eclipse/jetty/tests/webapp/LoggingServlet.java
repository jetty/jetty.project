// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.tests.webapp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet implementation class LoggingServlet
 */
public class LoggingServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;
    private static final String LOGID = "LoggingServlet(java)";
    private Logger log = Logger.getLogger(LoggingServlet.class.getName());

    /**
     * @see HttpServlet#HttpServlet()
     */
    public LoggingServlet()
    {
        log.log(Level.FINE,LOGID + " initialized");
    }

    /**
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        log.log(Level.INFO,LOGID + " GET requested");

        log.log(Level.WARNING,LOGID + " Slightly warn, with a chance of log events");

        log.log(Level.WARNING,LOGID + " Nothing is (intentionally) being output by this Servlet");

        IOException severe = new FileNotFoundException("A file cannot be found");

        log.log(Level.SEVERE,LOGID + " Whoops (intentionally) causing a Throwable",severe);

    }
}
