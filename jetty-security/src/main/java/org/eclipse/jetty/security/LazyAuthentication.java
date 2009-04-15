// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.security;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

import javax.security.auth.Subject;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class LazyAuthentication implements Authentication
{
    protected final Authenticator _authenticator;
    protected final ServletRequest _request;
    protected final ServletResponse _response;

    private Authentication _delegate;

    public LazyAuthentication(Authenticator authenticator, ServletRequest request, ServletResponse response)
    {
        if (authenticator == null)
            throw new NullPointerException("No Authenticator");
        this._authenticator = authenticator;
        this._request = request;
        this._response = response;
    }

    private Authentication getDelegate()
    {
        if (_delegate == null)
        {
            try
            {
                _delegate = _authenticator.validateRequest(_request,__nullResponse,false);
                if (_delegate.isSend())
                    _delegate=Authentication.NOT_CHECKED;
            }
            catch (ServerAuthException e)
            {
                Log.debug(e);
                _delegate = Authentication.FAILED;
            }
        }
        return _delegate;
    }

    public boolean isSuccess()
    {
        return getDelegate().isSuccess();
    }

    public boolean isSend()
    {
        return false;
    }

    public UserIdentity getUserIdentity()
    {
        return getDelegate().getUserIdentity();
    }

    public String getAuthMethod()
    {
        return getDelegate().getAuthMethod();
    }

    public void logout()
    {
        if (_delegate != null)
            _delegate.logout();
    }

    public String toString()
    {
        return "{Lazy," + _delegate + "}";
    }

    private static HttpServletResponse __nullResponse = new HttpServletResponse()
    {
        public void addCookie(Cookie cookie)
        {
        }

        public void addDateHeader(String name, long date)
        {
        }

        public void addHeader(String name, String value)
        {
        }

        public void addIntHeader(String name, int value)
        {
        }

        public boolean containsHeader(String name)
        {
            return false;
        }

        public String encodeRedirectURL(String url)
        {
            return null;
        }

        public String encodeRedirectUrl(String url)
        {
            return null;
        }

        public String encodeURL(String url)
        {
            return null;
        }

        public String encodeUrl(String url)
        {
            return null;
        }

        public void sendError(int sc) throws IOException
        {
        }

        public void sendError(int sc, String msg) throws IOException
        {
        }

        public void sendRedirect(String location) throws IOException
        {
        }

        public void setDateHeader(String name, long date)
        {
        }

        public void setHeader(String name, String value)
        {
        }

        public void setIntHeader(String name, int value)
        {
        }

        public void setStatus(int sc)
        {
        }

        public void setStatus(int sc, String sm)
        {
        }

        public void flushBuffer() throws IOException
        {
        }

        public int getBufferSize()
        {
            return 1024;
        }

        public String getCharacterEncoding()
        {
            return null;
        }

        public String getContentType()
        {
            return null;
        }

        public Locale getLocale()
        {
            return null;
        }

        public ServletOutputStream getOutputStream() throws IOException
        {
            return __nullOut;
        }

        public PrintWriter getWriter() throws IOException
        {
            return IO.getNullPrintWriter();
        }

        public boolean isCommitted()
        {
            return true;
        }

        public void reset()
        {
        }

        public void resetBuffer()
        {
        }

        public void setBufferSize(int size)
        {
        }

        public void setCharacterEncoding(String charset)
        {
        }

        public void setContentLength(int len)
        {
        }

        public void setContentType(String type)
        {
        }

        public void setLocale(Locale loc)
        {
        }

    };

    private static ServletOutputStream __nullOut = new ServletOutputStream()
    {
        public void write(int b) throws IOException
        {
        }

        public void print(String s) throws IOException
        {
        }

        public void println(String s) throws IOException
        {
        }
    };
}
