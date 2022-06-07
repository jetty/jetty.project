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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.Part;

/**
 * Dump Servlet Request.
 */
@SuppressWarnings("serial")
public class Dump extends HttpServlet
{
    /**
     * Zero Width Space, to allow text to be wrapped at designated spots
     */
    private static final String ZWSP = "&#8203;";
    boolean fixed;
    Timer _timer;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);

        if (config.getInitParameter("unavailable") != null && !fixed)
        {

            fixed = true;
            throw new UnavailableException("Unavailable test", Integer.parseInt(config.getInitParameter("unavailable")));
        }

        _timer = new Timer(true);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    public void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        byte[] buffer = new byte[8192];
        int len = request.getContentLength();
        int c = 0;
        InputStream in = request.getInputStream();
        while (c < len)
        {
            int l = in.read(buffer);
            if (l < 0)
                break;
            c += l;
        }
        request.setAttribute("PUT", c + "bytes");
        doGet(request, response);
    }

    @Override
    public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
    {
        if (request.getRemoteUser() == null)
        {
            try
            {
                request.login("user", "password");
            }
            catch (ServletException e)
            {
                getServletContext().log("Login fail", e);
            }
        }

        // Handle a dump of data
        final String data = request.getParameter("data");
        final String chars = request.getParameter("chars");
        final String block = request.getParameter("block");
        final String dribble = request.getParameter("dribble");
        final boolean flush = request.getParameter("flush") != null ? Boolean.parseBoolean(request.getParameter("flush")) : false;

        if (request.getPathInfo() != null && request.getPathInfo().toLowerCase(Locale.ENGLISH).indexOf("script") != -1)
        {
            response.sendRedirect(response.encodeRedirectURL(getServletContext().getContextPath() + "/dump/info"));
            return;
        }

        request.setCharacterEncoding("UTF-8");

        if (request.getParameter("busy") != null)
        {
            long end = System.currentTimeMillis() + Long.parseLong(request.getParameter("busy"));
            while (System.currentTimeMillis() < end)
            {
            }
        }

        if (request.getParameter("empty") != null)
        {
            response.setStatus(200);
            response.flushBuffer();
            return;
        }

        if (request.getParameter("sleep") != null)
        {
            try
            {
                long s = Long.parseLong(request.getParameter("sleep"));
                if (request.getHeader("Expect") != null && request.getHeader("Expect").indexOf("102") >= 0)
                {
                    Thread.sleep(s / 2);
                    response.sendError(102);
                    Thread.sleep(s / 2);
                }
                else
                    Thread.sleep(s);
            }
            catch (InterruptedException e)
            {
                return;
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        if (request.getParameter("startAsync") != null && request.getAttribute("ASYNC") != Boolean.TRUE)
        {
            request.setAttribute("ASYNC", Boolean.TRUE);
            try
            {
                final AsyncContext async = request.startAsync(request, response);
                async.setTimeout(Long.parseLong(request.getParameter("startAsync")));
                async.addListener(new AsyncListener()
                {

                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                        response.addHeader("Dump", "onTimeout");
                        try
                        {
                            if (!dump(response, data, chars, block, dribble, flush))
                            {
                                response.setContentType("text/plain");
                                response.getOutputStream().println("EXPIRED");
                            }
                            async.complete();
                        }
                        catch (IOException e)
                        {
                            getServletContext().log("", e);
                        }
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException
                    {
                        response.addHeader("Dump", "onStartAsync");
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        response.addHeader("Dump", "onError");
                    }

                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                        response.addHeader("Dump", "onComplete");
                    }
                });

                if (request.getParameter("dispatch") != null)
                {
                    request.setAttribute("RESUME", Boolean.TRUE);

                    final long resume = Long.parseLong(request.getParameter("dispatch"));
                    _timer.schedule(new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            async.dispatch();
                        }
                    }, resume);
                }

                if (request.getParameter("complete") != null)
                {
                    final long complete = Long.parseLong(request.getParameter("complete"));
                    _timer.schedule(new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                response.setContentType("text/html");
                                response.getOutputStream().println("<h1>COMPLETED</h1>");
                                async.complete();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }, complete);
                }

                return;
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }

        request.setAttribute("Dump", this);
        getServletContext().setAttribute("Dump", this);
        // getServletContext().log("dump "+request.getRequestURI());

        // Force a content length response
        String length = request.getParameter("length");
        if (length != null && length.length() > 0)
        {
            response.setContentLength(Integer.parseInt(length));
        }

        // Handle a dump of data
        if (dump(response, data, chars, block, dribble, flush))
            return;

        // handle an exception
        String info = request.getPathInfo();
        if (info != null && info.endsWith("Exception"))
        {
            try
            {
                throw (Throwable)Thread.currentThread().getContextClassLoader()
                    .loadClass(info.substring(1)).getDeclaredConstructor().newInstance();
            }
            catch (Throwable th)
            {
                throw new ServletException(th);
            }
        }

        // test a reset
        String reset = request.getParameter("reset");
        if (reset != null && reset.length() > 0)
        {
            response.getOutputStream().println("THIS SHOULD NOT BE SEEN!");
            response.setHeader("SHOULD_NOT", "BE SEEN");
            response.reset();
        }

        // handle an redirect
        String redirect = request.getParameter("redirect");
        if (redirect != null && redirect.length() > 0)
        {
            response.getOutputStream().println("THIS SHOULD NOT BE SEEN!");
            response.sendRedirect(response.encodeRedirectURL(redirect));
            try
            {
                response.getOutputStream().println("THIS SHOULD NOT BE SEEN!");
            }
            catch (IOException e)
            {
                // ignored as stream is closed.
            }
            return;
        }

        // handle an error
        String error = request.getParameter("error");
        if (error != null && error.length() > 0 && request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) == null)
        {
            response.getOutputStream().println("THIS SHOULD NOT BE SEEN!");
            response.sendError(Integer.parseInt(error));
            try
            {
                response.getOutputStream().println("THIS SHOULD NOT BE SEEN!");
            }
            catch (IllegalStateException e)
            {
                try
                {
                    response.getWriter().println("NOR THIS!!");
                }
                catch (IOException e2)
                {
                    getServletContext().log("Write fail", e2);
                }
            }
            catch (IOException e)
            {
                getServletContext().log("Output fail", e);
            }
            return;
        }

        // Handle a extra headers
        String headers = request.getParameter("headers");
        if (headers != null && headers.length() > 0)
        {
            long h = Long.parseLong(headers);
            for (int i = 0; i < h; i++)
            {
                response.addHeader("Header" + i, "Value" + i);
            }
        }

        String buffer = request.getParameter("buffer");
        if (buffer != null && buffer.length() > 0)
            response.setBufferSize(Integer.parseInt(buffer));

        String charset = request.getParameter("charset");
        if (charset == null)
            charset = "UTF-8";
        response.setCharacterEncoding(charset);
        response.setContentType("text/html");

        if (info != null && info.indexOf("Locale/") >= 0)
        {
            try
            {
                String localeName = info.substring(info.indexOf("Locale/") + 7);
                Field f = java.util.Locale.class.getField(localeName);
                response.setLocale((Locale)f.get(null));
            }
            catch (Exception e)
            {
                e.printStackTrace();
                response.setLocale(Locale.getDefault());
            }
        }

        String cn = request.getParameter("cookie");
        String cv = request.getParameter("cookiev");
        if (cn != null && cv != null)
        {
            Cookie cookie = new Cookie(cn, cv);
            if (request.getParameter("version") != null)
                cookie.setVersion(Integer.parseInt(request.getParameter("version")));
            cookie.setComment("Cookie from dump servlet");
            response.addCookie(cookie);
        }

        String pi = request.getPathInfo();
        if (pi != null && pi.startsWith("/ex"))
        {
            OutputStream out = response.getOutputStream();
            out.write("</h1>This text should be reset</h1>".getBytes());
            if ("/ex0".equals(pi))
                throw new ServletException("test ex0", new Throwable());
            else if ("/ex1".equals(pi))
                throw new IOException("test ex1");
            else if ("/ex2".equals(pi))
                throw new UnavailableException("test ex2");
            else if (pi.startsWith("/ex3/"))
                throw new UnavailableException("test ex3", Integer.parseInt(pi.substring(5)));
            throw new RuntimeException("test");
        }

        if ("true".equals(request.getParameter("close")))
            response.setHeader("Connection", "close");

        String buffered = request.getParameter("buffered");

        PrintWriter pout = null;

        try
        {
            pout = response.getWriter();
        }
        catch (IllegalStateException e)
        {
            pout = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), charset));
        }
        if (buffered != null)
            pout = new PrintWriter(new BufferedWriter(pout, Integer.parseInt(buffered)));

        try
        {
            pout.write("<html>\n<body>\n");
            pout.write("<h1>Dump Servlet</h1>\n");
            pout.write("<table width=\"95%\">");
            pout.write("<tr>\n");
            pout.write("<th align=\"right\">getContentLength:&nbsp;</th>");
            pout.write("<td>" + Integer.toString(request.getContentLength()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getContentType:&nbsp;</th>");
            pout.write("<td>" + notag(request.getContentType()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getContextPath:&nbsp;</th>");
            pout.write("<td>" + request.getContextPath() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getDispatcherType:&nbsp;</th>");
            pout.write("<td>" + request.getDispatcherType() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocale:&nbsp;</th>");
            pout.write("<td>" + request.getLocale() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocalName:&nbsp;</th>");
            pout.write("<td>" + request.getLocalName() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocalAddr:&nbsp;</th>");
            pout.write("<td>" + request.getLocalAddr() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getLocalPort:&nbsp;</th>");
            pout.write("<td>" + Integer.toString(request.getLocalPort()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getMethod:&nbsp;</th>");
            pout.write("<td>" + notag(request.getMethod()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getPathInfo:&nbsp;</th>");
            pout.write("<td>" + notag(request.getPathInfo()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getPathTranslated:&nbsp;</th>");
            pout.write("<td>" + notag(request.getPathTranslated()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getProtocol:&nbsp;</th>");
            pout.write("<td>" + request.getProtocol() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getQueryString:&nbsp;</th>");
            pout.write("<td>" + notag(request.getQueryString()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemoteAddr:&nbsp;</th>");
            pout.write("<td>" + request.getRemoteAddr() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemoteHost:&nbsp;</th>");
            pout.write("<td>" + request.getRemoteHost() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemotePort:&nbsp;</th>");
            pout.write("<td>" + request.getRemotePort() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRemoteUser:&nbsp;</th>");
            pout.write("<td>" + request.getRemoteUser() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRequestedSessionId:&nbsp;</th>");
            pout.write("<td>" + request.getRequestedSessionId() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRequestURI:&nbsp;</th>");
            pout.write("<td>" + notag(request.getRequestURI()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getRequestURL:&nbsp;</th>");
            pout.write("<td>" + notag(request.getRequestURL().toString()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getScheme:&nbsp;</th>");
            pout.write("<td>" + request.getScheme() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getServerName:&nbsp;</th>");
            pout.write("<td>" + notag(request.getServerName()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getServletPath:&nbsp;</th>");
            pout.write("<td>" + notag(request.getServletPath()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getServerPort:&nbsp;</th>");
            pout.write("<td>" + Integer.toString(request.getServerPort()) + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">getUserPrincipal:&nbsp;</th>");
            pout.write("<td>" + request.getUserPrincipal() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">isAsyncStarted():&nbsp;</th>");
            pout.write("<td>" + request.isAsyncStarted() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">isAsyncSupported():&nbsp;</th>");
            pout.write("<td>" + request.isAsyncSupported() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">isSecure():&nbsp;</th>");
            pout.write("<td>" + request.isSecure() + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">isUserInRole(admin):&nbsp;</th>");
            pout.write("<td>" + request.isUserInRole("admin") + "</td>");
            pout.write("</tr><tr>\n");
            pout.write("<th align=\"right\">encodeRedirectURL(/foo?bar):&nbsp;</th>");
            pout.write("<td>" + response.encodeRedirectURL("/foo?bar") + "</td>");

            Enumeration<Locale> locales = request.getLocales();
            while (locales.hasMoreElements())
            {
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">getLocales:&nbsp;</th>");
                pout.write("<td>" + locales.nextElement() + "</td>");
            }
            pout.write("</tr><tr>\n");

            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Other HTTP Headers:</big></th>");
            Enumeration<String> h = request.getHeaderNames();
            String name;
            while (h.hasMoreElements())
            {
                name = (String)h.nextElement();

                Enumeration<String> h2 = request.getHeaders(name);
                while (h2.hasMoreElements())
                {
                    String hv = (String)h2.nextElement();
                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"right\">" + notag(name) + ":&nbsp;</th>");
                    pout.write("<td>" + notag(hv) + "</td>");
                }
            }

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Request Parameters:</big></th>");
            h = request.getParameterNames();
            while (h.hasMoreElements())
            {
                name = (String)h.nextElement();
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">" + notag(name) + ":&nbsp;</th>");
                pout.write("<td>" + notag(request.getParameter(name)) + "</td>");
                String[] values = request.getParameterValues(name);
                if (values == null)
                {
                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"right\">" + notag(name) + " Values:&nbsp;</th>");
                    pout.write("<td>" + "NULL!" + "</td>");
                }
                else if (values.length > 1)
                {
                    for (int i = 0; i < values.length; i++)
                    {
                        pout.write("</tr><tr>\n");
                        pout.write("<th align=\"right\">" + notag(name) + "[" + i + "]:&nbsp;</th>");
                        pout.write("<td>" + notag(values[i]) + "</td>");
                    }
                }
            }

            try
            {
                Collection<Part> parts = request.getParts();
                if (parts != null && !parts.isEmpty())
                {
                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Parts:</big></th>");
                    for (Part p : parts)
                    {
                        pout.write("</tr><tr>\n");
                        pout.write("<th align=\"right\">" + notag(p.getName()) + ":&nbsp;</th>");
                        pout.write("<td>" + p + "</td>");
                    }
                }
            }
            catch (ServletException e)
            {
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"left\" colspan=\"2\"><big><br/>No Parts!</big></th>");
            }

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Cookies:</big></th>");
            Cookie[] cookies = request.getCookies();
            for (int i = 0; cookies != null && i < cookies.length; i++)
            {
                Cookie cookie = cookies[i];

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">" + notag(cookie.getName()) + ":&nbsp;</th>");
                pout.write("<td>" + notag(cookie.getValue()) + "</td>");
            }

            String contentType = request.getContentType();
            if (contentType != null &&
                !contentType.startsWith("application/x-www-form-urlencoded") &&
                !contentType.startsWith("multipart/form-data"))
            {
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"left\" valign=\"top\" colspan=\"2\"><big><br/>Content:</big></th>");
                pout.write("</tr><tr>\n");
                pout.write("<td><pre>");
                char[] content = new char[4096];
                int len;
                try
                {
                    Reader in = request.getReader();

                    while ((len = in.read(content)) >= 0)
                    {
                        pout.write(notag(new String(content, 0, len)));
                    }
                }
                catch (IOException e)
                {
                    pout.write(e.toString());
                }

                pout.write("</pre></td>");
            }

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Request Attributes:</big></th>");
            Enumeration<String> a = request.getAttributeNames();
            while (a.hasMoreElements())
            {
                name = (String)a.nextElement();
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\" valign=\"top\">" + name.replace(".", ZWSP + ".") + ":&nbsp;</th>");
                Object value = request.getAttribute(name);
                if (value instanceof File)
                {
                    File file = (File)value;
                    pout.write("<td>" + "<pre>" + file.getName() + " (" + file.length() + " " + new Date(file.lastModified()) + ")</pre>" + "</td>");
                }
                else
                    pout.write("<td>" + "<pre>" + toString(request.getAttribute(name)) + "</pre>" + "</td>");
            }
            request.setAttribute("org.eclipse.jetty.ee9.servlet.MultiPartFilter.files", null);

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Servlet InitParameters:</big></th>");
            a = getInitParameterNames();
            while (a.hasMoreElements())
            {
                name = (String)a.nextElement();
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">" + name + ":&nbsp;</th>");
                pout.write("<td>" + toString(getInitParameter(name)) + "</td>");
            }

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Context InitParameters:</big></th>");
            a = getServletContext().getInitParameterNames();
            while (a.hasMoreElements())
            {
                name = (String)a.nextElement();
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\" valign=\"top\">" + name.replace(".", ZWSP + ".") + ":&nbsp;</th>");
                pout.write("<td>" + toString(getServletContext().getInitParameter(name)) + "</td>");
            }

            pout.write("</tr><tr>\n");
            pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Context Attributes:</big></th>");
            a = getServletContext().getAttributeNames();
            while (a.hasMoreElements())
            {
                name = (String)a.nextElement();
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\" valign=\"top\">" + name.replace(".", ZWSP + ".") + ":&nbsp;</th>");
                pout.write("<td>" + "<pre>" + toString(getServletContext().getAttribute(name)) + "</pre>" + "</td>");
            }

            String res = request.getParameter("resource");
            if (res != null && res.length() > 0)
            {
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"left\" colspan=\"2\"><big><br/>Get Resource: \"" + res + "\"</big></th>");

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">getServletContext().getResource(...):&nbsp;</th>");
                try
                {
                    pout.write("<td>" + getServletContext().getResource(res) + "</td>");
                }
                catch (Exception e)
                {
                    pout.write("<td>" + "" + e + "</td>");
                }

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">getServletContext().getResourcePaths(...):&nbsp;</th>");
                try
                {
                    pout.write("<td>" + getServletContext().getResourcePaths(res) + "</td>");
                }
                catch (Exception e)
                {
                    pout.write("<td>" + "" + e + "</td>");
                }

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">getServletContext().getRealPath(...):&nbsp;</th>");
                try
                {
                    pout.write("<td>" + getServletContext().getRealPath(res) + "</td>");
                }
                catch (Exception e)
                {
                    pout.write("<td>" + "" + e + "</td>");
                }

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">getServletContext().getContext(...):&nbsp;</th>");

                ServletContext context = getServletContext().getContext(res);
                pout.write("<td>" + context + "</td>");

                if (context != null)
                {
                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"right\">getServletContext().getContext(...).getResource(...):&nbsp;</th>");
                    try
                    {
                        pout.write("<td>" + context.getResource(res) + "</td>");
                    }
                    catch (Exception e)
                    {
                        pout.write("<td>" + "" + e + "</td>");
                    }

                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"right\">getServletContext().getContext(...).getResourcePaths(...):&nbsp;</th>");
                    try
                    {
                        pout.write("<td>" + context.getResourcePaths(res) + "</td>");
                    }
                    catch (Exception e)
                    {
                        pout.write("<td>" + "" + e + "</td>");
                    }

                    String cp = context.getContextPath();
                    if (cp == null || "/".equals(cp))
                        cp = "";
                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"right\">getServletContext().getContext(...).getRequestDispatcher(...):&nbsp;</th>");
                    pout.write("<td>" + context.getRequestDispatcher(res.substring(cp.length())) + "</td>");

                    pout.write("</tr><tr>\n");
                    pout.write("<th align=\"right\">getServletContext().getContext(...).getRealPath(...):&nbsp;</th>");
                    pout.write("<td>" + context.getRealPath(res.substring(cp.length())) + "</td>");
                }

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">this.getClass().getResource(...):&nbsp;</th>");
                pout.write("<td>" + this.getClass().getResource(res) + "</td>");

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">this.getClass().getClassLoader().getResource(...):&nbsp;</th>");
                pout.write("<td>" + this.getClass().getClassLoader().getResource(res) + "</td>");

                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">Thread.currentThread().getContextClassLoader().getResource(...):&nbsp;</th>");
                pout.write("<td>" + Thread.currentThread().getContextClassLoader().getResource(res) + "</td>");
                pout.write("</tr><tr>\n");
                pout.write("<th align=\"right\">Thread.currentThread().getContextClassLoader().getResources(...):&nbsp;</th>");
                Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources(res);
                if (urls == null)
                    pout.write("<td>null</td>");
                else
                    pout.write("<td>" + Collections.list(urls) + "</td>");
            }

            pout.write("</tr></table>\n");

            pout.write("<h2>Request Wrappers</h2>\n");
            ServletRequest rw = request;
            int w = 0;
            while (rw != null)
            {
                pout.write((w++) + ": " + rw.getClass().getName() + "<br/>");
                if (rw instanceof HttpServletRequestWrapper)
                    rw = ((HttpServletRequestWrapper)rw).getRequest();
                else if (rw instanceof ServletRequestWrapper)
                    rw = ((ServletRequestWrapper)rw).getRequest();
                else
                    rw = null;
            }

            pout.write("<h2>Response Wrappers</h2>\n");
            ServletResponse rsw = response;
            w = 0;
            while (rsw != null)
            {
                pout.write((w++) + ": " + rsw.getClass().getName() + "<br/>");
                if (rsw instanceof HttpServletResponseWrapper)
                    rsw = ((HttpServletResponseWrapper)rsw).getResponse();
                else if (rsw instanceof ServletResponseWrapper)
                    rsw = ((ServletResponseWrapper)rsw).getResponse();
                else
                    rsw = null;
            }

            pout.write("<br/>");
            pout.write("<h2>International Characters (UTF-8)</h2>");
            pout.write("LATIN LETTER SMALL CAPITAL AE<br/>\n");
            pout.write("Directly uni encoded(\\u1d01): \u1d01<br/>"); // uni encoded
            pout.write("HTML reference (&amp;AElig;): &AElig;<br/>");
            pout.write("Decimal (&amp;#7425;): &#7425;<br/>");
            pout.write("Javascript unicode (\\u1d01) : <script language='javascript'>document.write(\"\u1d01\");</script><br/>"); // uni encoded
            pout.write("<br/>");
            pout.write("<h2>Form to generate GET content</h2>");
            pout.write("<form method=\"GET\" action=\"" + response.encodeURL(getURI(request)) + "\">");
            pout.write("TextField: <input type=\"text\" name=\"TextField\" value=\"value\"/><br/>\n");
            pout.write("<input type=\"submit\" name=\"Action\" value=\"Submit\">");
            pout.write("</form>");

            pout.write("<br/>");

            pout.write("<h2>Form to generate POST content</h2>");
            pout.write("<form method=\"POST\" accept-charset=\"utf-8\" action=\"" + response.encodeURL(getURI(request)) + "\">");
            pout.write("TextField: <input type=\"text\" name=\"TextField\" value=\"value\"/><br/>\n");
            pout.write("Select: <select multiple name=\"Select\">\n");
            pout.write("<option>ValueA</option>");
            pout.write("<option>ValueB1,ValueB2</option>");
            pout.write("<option>ValueC</option>");
            pout.write("</select><br/>");
            pout.write("<input type=\"submit\" name=\"Action\" value=\"Submit\"><br/>");
            pout.write("</form>");
            pout.write("<br/>");

            pout.write("<h2>Form to generate UPLOAD content</h2>");
            pout.write("<form method=\"POST\" enctype=\"multipart/form-data\" accept-charset=\"utf-8\" action=\"" +
                response.encodeURL(getURI(request)) + (request.getQueryString() == null ? "" : ("?" + request.getQueryString())) +
                "\">");
            pout.write("TextField: <input type=\"text\" name=\"TextField\" value=\"comment\"/><br/>\n");
            pout.write("File 1: <input type=\"file\" name=\"file1\" /><br/>\n");
            pout.write("File 2: <input type=\"file\" name=\"file2\" /><br/>\n");
            pout.write("<input type=\"submit\" name=\"Action\" value=\"Submit\"><br/>");
            pout.write("</form>");

            pout.write("<h2>Form to set Cookie</h2>");
            pout.write("<form method=\"POST\" action=\"" + response.encodeURL(getURI(request)) + "\">");
            pout.write("cookie: <input type=\"text\" name=\"cookie\" /><br/>\n");
            pout.write("value: <input type=\"text\" name=\"cookiev\" /><br/>\n");
            pout.write("<input type=\"submit\" name=\"Action\" value=\"setCookie\">");
            pout.write("</form>\n");

            pout.write("<h2>Form to get Resource</h2>");
            pout.write("<form method=\"POST\" action=\"" + response.encodeURL(getURI(request)) + "\">");
            pout.write("resource: <input type=\"text\" name=\"resource\" /><br/>\n");
            pout.write("<input type=\"submit\" name=\"Action\" value=\"getResource\">");
            pout.write("</form>\n");
        }
        catch (Exception e)
        {
            getServletContext().log("dump " + e);
        }

        String lines = request.getParameter("lines");
        if (lines != null)
        {
            char[] line = "<span>A line of characters. Blah blah blah blah.  blooble blooble</span></br>\n".toCharArray();
            for (int l = Integer.parseInt(lines); l-- > 0; )
            {
                pout.write("<span>" + l + " </span>");
                pout.write(line);
            }
        }

        pout.write("</body>\n</html>\n");

        pout.close();

        if (pi != null)
        {
            if ("/ex4".equals(pi))
                throw new ServletException("test ex4", new Throwable());
            if ("/ex5".equals(pi))
                throw new IOException("test ex5");
            if ("/ex6".equals(pi))
                throw new UnavailableException("test ex6");
        }
    }

    @Override
    public String getServletInfo()
    {
        return "Dump Servlet";
    }

    @Override
    public void destroy()
    {
        _timer.cancel();
    }

    private String getURI(HttpServletRequest request)
    {
        String uri = (String)request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if (uri == null)
            uri = request.getRequestURI();
        return uri;
    }

    private static String toString(Object o)
    {
        if (o == null)
            return null;

        try
        {
            if (o.getClass().isArray())
            {
                StringBuffer sb = new StringBuffer();
                if (!o.getClass().getComponentType().isPrimitive())
                {
                    Object[] array = (Object[])o;
                    for (int i = 0; i < array.length; i++)
                    {
                        if (i > 0)
                            sb.append("\n");
                        sb.append(array.getClass().getComponentType().getName());
                        sb.append("[");
                        sb.append(i);
                        sb.append("]=");
                        sb.append(toString(array[i]));
                    }
                    return sb.toString();
                }
                else
                {
                    int length = Array.getLength(o);
                    for (int i = 0; i < length; i++)
                    {
                        if (i > 0)
                            sb.append("\n");
                        sb.append(o.getClass().getComponentType().getName());
                        sb.append("[");
                        sb.append(i);
                        sb.append("]=");
                        sb.append(toString(Array.get(o, i)));
                    }
                    return sb.toString();
                }
            }
            else
                return o.toString();
        }
        catch (Exception e)
        {
            return e.toString();
        }
    }

    private boolean dump(HttpServletResponse response, String data, String chars, String block, String dribble, boolean flush) throws IOException
    {
        if (data != null && data.length() > 0)
        {
            int b = (block != null && block.length() > 0) ? Integer.parseInt(block) : 50;
            byte[] buf = new byte[b];
            for (int i = 0; i < b; i++)
            {

                buf[i] = (byte)('0' + (i % 10));
                if (i % 10 == 9)
                    buf[i] = (byte)'\n';
            }
            buf[0] = 'o';
            OutputStream out = response.getOutputStream();
            response.setContentType("text/plain");
            long d = Long.parseLong(data);
            while (d > 0)
            {
                if (b == 1)
                {
                    out.write(d % 80 == 0 ? '\n' : '.');
                    d--;
                }
                else if (d >= b)
                {
                    out.write(buf);
                    d = d - b;
                }
                else
                {
                    out.write(buf, 0, (int)d);
                    d = 0;
                }

                if (dribble != null)
                {
                    out.flush();
                    try
                    {
                        Thread.sleep(Long.parseLong(dribble));
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        break;
                    }
                }
            }

            if (flush)
                out.flush();

            return true;
        }

        // Handle a dump of data
        if (chars != null && chars.length() > 0)
        {
            int b = (block != null && block.length() > 0) ? Integer.parseInt(block) : 50;
            char[] buf = new char[b];
            for (int i = 0; i < b; i++)
            {
                buf[i] = (char)('0' + (i % 10));
                if (i % 10 == 9)
                    buf[i] = '\n';
            }
            buf[0] = 'o';
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            long d = Long.parseLong(chars);
            while (d > 0 && !out.checkError())
            {
                if (b == 1)
                {
                    out.write(d % 80 == 0 ? '\n' : '.');
                    d--;
                }
                else if (d >= b)
                {
                    out.write(buf);
                    d = d - b;
                }
                else
                {
                    out.write(buf, 0, (int)d);
                    d = 0;
                }
            }
            return true;
        }
        return false;
    }

    private String notag(String s)
    {
        if (s == null)
            return "null";
        s = s.replace("&", "&amp;");
        s = s.replace("<", "&lt;");
        s = s.replace(">", "&gt;");
        return s;
    }
}
