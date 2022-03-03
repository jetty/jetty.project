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

package org.example;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Test Servlet RequestDispatcher.
 */
@SuppressWarnings("serial")
public class DispatchServlet extends HttpServlet
{

    String pageType;

    @Override
    public void doPost(HttpServletRequest sreq, HttpServletResponse sres) throws ServletException, IOException
    {
        doGet(sreq, sres);
    }

    @Override
    public void doGet(HttpServletRequest sreq, HttpServletResponse sres) throws ServletException, IOException
    {
        if (sreq.getParameter("wrap") != null)
        {
            sreq = new HttpServletRequestWrapper(sreq);
            sres = new HttpServletResponseWrapper(sres);
        }

        if (sreq.getParameter("session") != null)
            sreq.getSession(true);

        String prefix =
            sreq.getContextPath() != null ? sreq.getContextPath() + sreq.getServletPath() : sreq.getServletPath();

        String info;

        if (sreq.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH) != null)
            info = (String)sreq.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
        else
            info = sreq.getPathInfo();

        if (info == null)
            info = "NULL";

        if (info.indexOf(sreq.getServletPath()) > 0)
        {
            sres.sendError(403, "Nested " + sreq.getServletPath() + " forbidden.");
            return;
        }

        if (info.indexOf(getServletName()) > 0)
        {
            sres.sendError(403, "Nested " + getServletName() + " forbidden.");
            return;
        }

        if (info.startsWith("/includeW/"))
        {
            sres.setContentType("text/html");
            info = info.substring(9);
            if (info.indexOf('?') < 0)
                info += "?Dispatch=include";
            else
                info += "&Dispatch=include";

            PrintWriter pout = null;
            pout = sres.getWriter();
            pout.write("<h1>Include (writer): " + info + "</h1><hR>");

            RequestDispatcher dispatch = getServletContext().getRequestDispatcher(info);
            if (dispatch == null)
            {
                pout = sres.getWriter();
                pout.write("<h1>Null dispatcher</h1>");
            }
            else
                dispatch.include(sreq, sres);

            pout.write("<hR><h1>-- Included (writer)</h1>");
        }
        else if (info.startsWith("/includeS/"))
        {
            sres.setContentType("text/html");
            info = info.substring(9);
            if (info.indexOf('?') < 0)
                info += "?Dispatch=include";
            else
                info += "&Dispatch=include";

            OutputStream out = null;
            out = sres.getOutputStream();
            out.write(("<h1>Include (outputstream): " + info + "</h1><hR>").getBytes());

            RequestDispatcher dispatch = getServletContext().getRequestDispatcher(info);
            if (dispatch == null)
            {
                out = sres.getOutputStream();
                out.write("<h1>Null dispatcher</h1>".getBytes());
            }
            else
                dispatch.include(sreq, sres);

            out.write("<hR><h1>-- Included (outputstream)</h1>".getBytes());
        }
        else if (info.startsWith("/forward/"))
        {
            info = info.substring(8);
            if (info.indexOf('?') < 0)
                info += "?Dispatch=forward";
            else
                info += "&Dispatch=forward";

            RequestDispatcher dispatch = getServletContext().getRequestDispatcher(info);
            if (dispatch != null)
            {
                ServletOutputStream out = sres.getOutputStream();
                out.print("Can't see this");
                dispatch.forward(sreq, sres);
                try
                {
                    // should be closed
                    out.println("IOException");
                    // should not get here
                    throw new IllegalStateException();
                }
                catch (IOException e)
                {
                    // getServletContext().log("ignore",e);
                }
            }
            else
            {
                sres.setContentType("text/html");
                PrintWriter pout = sres.getWriter();
                pout.write("<h1>No dispatcher for: " + info + "</h1><hR>");
                pout.flush();
            }
        }
        else if (info.startsWith("/forwardC/"))
        {
            info = info.substring(9);
            if (info.indexOf('?') < 0)
                info += "?Dispatch=forward";
            else
                info += "&Dispatch=forward";

            String cpath = info.substring(0, info.indexOf('/', 1));
            info = info.substring(cpath.length());

            ServletContext context = getServletContext().getContext(cpath);
            RequestDispatcher dispatch = context.getRequestDispatcher(info);

            if (dispatch != null)
            {
                dispatch.forward(sreq, sres);
            }
            else
            {
                sres.setContentType("text/html");
                PrintWriter pout = sres.getWriter();
                pout.write("<h1>No dispatcher for: " + cpath + "/" + info + "</h1><hR>");
                pout.flush();
            }
        }
        else if (info.startsWith("/includeN/"))
        {
            sres.setContentType("text/html");
            info = info.substring(10);
            if (info.indexOf("/") >= 0)
                info = info.substring(0, info.indexOf("/"));

            PrintWriter pout;
            if (info.startsWith("/null"))
                info = info.substring(5);
            else
            {
                pout = sres.getWriter();
                pout.write("<h1>Include named: " + info + "</h1><hR>");
            }

            RequestDispatcher dispatch = getServletContext().getNamedDispatcher(info);
            if (dispatch != null)
                dispatch.include(sreq, sres);
            else
            {
                pout = sres.getWriter();
                pout.write("<h1>No servlet named: " + info + "</h1>");
            }

            pout = sres.getWriter();
            pout.write("<hR><h1>Included ");
        }
        else if (info.startsWith("/forwardN/"))
        {
            info = info.substring(10);
            if (info.indexOf("/") >= 0)
                info = info.substring(0, info.indexOf("/"));
            RequestDispatcher dispatch = getServletContext().getNamedDispatcher(info);
            if (dispatch != null)
                dispatch.forward(sreq, sres);
            else
            {
                sres.setContentType("text/html");
                PrintWriter pout = sres.getWriter();
                pout.write("<h1>No servlet named: " + info + "</h1>");
                pout.flush();
            }
        }
        else
        {
            sres.setContentType("text/html");
            PrintWriter pout = sres.getWriter();
            pout.write(
                "<h1>Dispatch URL must be of the form: </h1>" +
                    "<PRE>" +
                    prefix + "/includeW/path\n" +
                    prefix + "/includeS/path\n" +
                    prefix + "/forward/path\n" +
                    prefix + "/includeN/name\n" +
                    prefix + "/forwardC/_context/path\n</PRE>");
        }
    }

    @Override
    public String getServletInfo()
    {
        return "Include Servlet";
    }
}
