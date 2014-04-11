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


package org.eclipse.jetty.security.authentication;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class DeferredAuthentication implements Authentication.Deferred
{
    private static final Logger LOG = Log.getLogger(DeferredAuthentication.class);
    protected final LoginAuthenticator _authenticator;
    private Object _previousAssociation;

    /* ------------------------------------------------------------ */
    public DeferredAuthentication(LoginAuthenticator authenticator)
    {
        if (authenticator == null)
            throw new NullPointerException("No Authenticator");
        this._authenticator = authenticator;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.Authentication.Deferred#authenticate(ServletRequest)
     */
    public Authentication authenticate(ServletRequest request)
    {
        try
        {
            Authentication authentication = _authenticator.validateRequest(request,__deferredResponse,true);
            
            if (authentication!=null && (authentication instanceof Authentication.User) && !(authentication instanceof Authentication.ResponseSent))
            {
                LoginService login_service= _authenticator.getLoginService();
                IdentityService identity_service=login_service.getIdentityService();
                
                if (identity_service!=null)
                    _previousAssociation=identity_service.associate(((Authentication.User)authentication).getUserIdentity());
                
                return authentication;
            }
        }
        catch (ServerAuthException e)
        {
            LOG.debug(e);
        }

        return this;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.Authentication.Deferred#authenticate(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public Authentication authenticate(ServletRequest request, ServletResponse response)
    {
        try
        {
            LoginService login_service= _authenticator.getLoginService();
            IdentityService identity_service=login_service.getIdentityService();
            
            Authentication authentication = _authenticator.validateRequest(request,response,true);
            if (authentication instanceof Authentication.User && identity_service!=null)
                _previousAssociation=identity_service.associate(((Authentication.User)authentication).getUserIdentity());
            return authentication;
        }
        catch (ServerAuthException e)
        {
            LOG.debug(e);
        }
        return this;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.Authentication.Deferred#login(java.lang.String, java.lang.String)
     */
    public Authentication login(String username, Object password, ServletRequest request)
    {
        UserIdentity identity = _authenticator.login(username, password, request);
        if (identity != null)
        {
            IdentityService identity_service = _authenticator.getLoginService().getIdentityService();
            UserAuthentication authentication = new UserAuthentication("API",identity);
            if (identity_service != null)
                _previousAssociation=identity_service.associate(identity);
            return authentication;
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    public Object getPreviousAssociation()
    {
        return _previousAssociation;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param response
     * @return true if this response is from a deferred call to {@link #authenticate(ServletRequest)}
     */
    public static boolean isDeferred(HttpServletResponse response)
    {
        return response==__deferredResponse;
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    final static HttpServletResponse __deferredResponse = new HttpServletResponse()
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

	public Collection<String> getHeaderNames()
	{
	    return Collections.emptyList();
	}

	@Override
	public String getHeader(String arg0)
	{
	    return null;
	}

	@Override
	public Collection<String> getHeaders(String arg0)
	{
            return Collections.emptyList();
	}

	@Override
	public int getStatus()
	{
	    return 0;
	}

    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
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
