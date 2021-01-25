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

package org.eclipse.jetty.continuation;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * <p>ContinuationFilter must be applied to servlet paths that make use of
 * the asynchronous features provided by {@link Continuation} APIs, but that
 * are deployed in servlet containers that are a
 * compliant Servlet 3.0 container.</p>
 * <p>The following init parameters may be used to configure the filter (these are mostly for testing):</p>
 * <dl>
 * <dt>debug</dt><dd>Boolean controlling debug output</dd>
 * <dt>faux</dt><dd>Boolean to force use of faux continuations</dd>
 * </dl>
 * <p>If the servlet container is not Jetty 7+ nor a Servlet 3
 * container, then "faux" continuations will be used.</p>
 * <p>Faux continuations will just put the thread that called {@link Continuation#suspend()}
 * in wait, and will notify that thread when {@link Continuation#resume()} or
 * {@link Continuation#complete()} is called.</p>
 * <p>Faux continuations are not threadless continuations (they are "faux" - fake - for this reason)
 * and as such they will scale less than proper continuations.</p>
 *
 * @deprecated use Servlet 3.0 {@link javax.servlet.AsyncContext} instead
 */
@Deprecated
public class ContinuationFilter implements Filter
{
    static boolean _initialized;
    static boolean __debug; // shared debug status
    private boolean _faux;
    private boolean _filtered;
    ServletContext _context;
    private boolean _debug;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        filterConfig.getServletContext().log("WARNING: " + this.getClass().getName() + " is now DEPRECATED, use Servlet 3.0 AsyncContext instead.");
        boolean jetty7OrGreater = "org.eclipse.jetty.servlet".equals(filterConfig.getClass().getPackage().getName());
        _context = filterConfig.getServletContext();

        String param = filterConfig.getInitParameter("debug");
        _debug = param != null && Boolean.parseBoolean(param);
        if (_debug)
            __debug = true;

        param = filterConfig.getInitParameter("partial");
        param = filterConfig.getInitParameter("faux");
        if (param != null)
            _faux = Boolean.parseBoolean(param);
        else
            _faux = !(jetty7OrGreater || _context.getMajorVersion() >= 3);

        _filtered = _faux;
        if (_debug)
            _context.log("ContinuationFilter " +
                " jetty=" + jetty7OrGreater +
                " faux=" + _faux +
                " filtered=" + _filtered +
                " servlet3=" + ContinuationSupport.__servlet3);
        _initialized = true;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (_filtered)
        {
            Continuation c = (Continuation)request.getAttribute(Continuation.ATTRIBUTE);
            FilteredContinuation fc;
            if (_faux && (c == null || !(c instanceof FauxContinuation)))
            {
                fc = new FauxContinuation(request);
                request.setAttribute(Continuation.ATTRIBUTE, fc);
            }
            else
                fc = (FilteredContinuation)c;

            boolean complete = false;
            while (!complete)
            {
                try
                {
                    if (fc == null || (fc).enter(response))
                        chain.doFilter(request, response);
                }
                catch (ContinuationThrowable e)
                {
                    debug("faux", e);
                }
                finally
                {
                    if (fc == null)
                        fc = (FilteredContinuation)request.getAttribute(Continuation.ATTRIBUTE);

                    complete = fc == null || (fc).exit();
                }
            }
        }
        else
        {
            try
            {
                chain.doFilter(request, response);
            }
            catch (ContinuationThrowable e)
            {
                debug("caught", e);
            }
        }
    }

    private void debug(String string)
    {
        if (_debug)
        {
            _context.log(string);
        }
    }

    private void debug(String string, Throwable th)
    {
        if (_debug)
        {
            if (th instanceof ContinuationThrowable)
                _context.log(string + ":" + th);
            else
                _context.log(string, th);
        }
    }

    @Override
    public void destroy()
    {
    }

    public interface FilteredContinuation extends Continuation
    {
        boolean enter(ServletResponse response);

        boolean exit();
    }
}
