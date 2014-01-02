//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.lang.reflect.Constructor;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;

/* ------------------------------------------------------------ */
/** ContinuationSupport.
 *
 * Factory class for accessing Continuation instances, which with either be
 * native to the container (jetty >= 6), a servlet 3.0 or a faux continuation.
 *
 */
public class ContinuationSupport
{
    static final boolean __jetty6;
    static final boolean __servlet3;
    static final Class<?> __waitingContinuation;
    static final Constructor<? extends Continuation> __newServlet3Continuation;
    static final Constructor<? extends Continuation> __newJetty6Continuation;
    static
    {
        boolean servlet3Support=false;
        Constructor<? extends Continuation>s3cc=null;
        try
        {
            boolean servlet3=ServletRequest.class.getMethod("startAsync")!=null;
            if (servlet3)
            {
                Class<? extends Continuation> s3c = ContinuationSupport.class.getClassLoader().loadClass("org.eclipse.jetty.continuation.Servlet3Continuation").asSubclass(Continuation.class);
                s3cc=s3c.getConstructor(ServletRequest.class);
                servlet3Support=true;
            }
        }
        catch (Exception e)
        {}
        finally
        {
            __servlet3=servlet3Support;
            __newServlet3Continuation=s3cc;
        }

        boolean jetty6Support=false;
        Constructor<? extends Continuation>j6cc=null;
        try
        {
            Class<?> jetty6ContinuationClass = ContinuationSupport.class.getClassLoader().loadClass("org.mortbay.util.ajax.Continuation");
            boolean jetty6=jetty6ContinuationClass!=null;
            if (jetty6)
            {
                Class<? extends Continuation> j6c = ContinuationSupport.class.getClassLoader().loadClass("org.eclipse.jetty.continuation.Jetty6Continuation").asSubclass(Continuation.class);
                j6cc=j6c.getConstructor(ServletRequest.class, jetty6ContinuationClass);
                jetty6Support=true;
            }
        }
        catch (Exception e)
        {}
        finally
        {
            __jetty6=jetty6Support;
            __newJetty6Continuation=j6cc;
        }

        Class<?> waiting=null;
        try
        {
            waiting=ContinuationSupport.class.getClassLoader().loadClass("org.mortbay.util.ajax.WaitingContinuation");
        }
        catch (Exception e)
        {
        }
        finally
        {
            __waitingContinuation=waiting;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get a Continuation.  The type of the Continuation returned may
     * vary depending on the container in which the application is
     * deployed. It may be an implementation native to the container (eg
     * org.eclipse.jetty.server.AsyncContinuation) or one of the utility
     * implementations provided such as an internal <code>FauxContinuation</code>
     * or a real implementation like {@link org.eclipse.jetty.continuation.Servlet3Continuation}.
     * @param request The request
     * @return a Continuation instance
     */
    public static Continuation getContinuation(ServletRequest request)
    {
        Continuation continuation = (Continuation) request.getAttribute(Continuation.ATTRIBUTE);
        if (continuation!=null)
            return continuation;

        while (request instanceof ServletRequestWrapper)
            request=((ServletRequestWrapper)request).getRequest();

        if (__servlet3 )
        {
            try
            {
                continuation=__newServlet3Continuation.newInstance(request);
                request.setAttribute(Continuation.ATTRIBUTE,continuation);
                return continuation;
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        if (__jetty6)
        {
            Object c=request.getAttribute("org.mortbay.jetty.ajax.Continuation");
            try
            {
                if (c==null || __waitingContinuation==null || __waitingContinuation.isInstance(c))
                    continuation=new FauxContinuation(request);
                else
                    continuation= __newJetty6Continuation.newInstance(request,c);
                request.setAttribute(Continuation.ATTRIBUTE,continuation);
                return continuation;
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        throw new IllegalStateException("!(Jetty || Servlet 3.0 || ContinuationFilter)");
    }

    /* ------------------------------------------------------------ */
    /**
     * @param request the servlet request
     * @param response the servlet response
     * @deprecated use {@link #getContinuation(ServletRequest)}
     * @return the continuation
     */
    @Deprecated
    public static Continuation getContinuation(final ServletRequest request, final ServletResponse response)
    {
        return getContinuation(request);
    }
}
