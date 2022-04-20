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

package org.eclipse.jetty.ee9.security.authentication;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.IdentityService;
import org.eclipse.jetty.ee9.security.LoggedOutAuthentication;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.ee9.security.SecurityHandler;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeferredAuthentication implements Authentication.Deferred
{
    private static final Logger LOG = LoggerFactory.getLogger(DeferredAuthentication.class);
    protected final LoginAuthenticator _authenticator;
    private Object _previousAssociation;

    public DeferredAuthentication(LoginAuthenticator authenticator)
    {
        if (authenticator == null)
            throw new NullPointerException("No Authenticator");
        this._authenticator = authenticator;
    }

    @Override
    public Authentication authenticate(ServletRequest request)
    {
        try
        {
            Authentication authentication = _authenticator.validateRequest(request, __deferredResponse, true);
            if (authentication != null && (authentication instanceof Authentication.User) && !(authentication instanceof Authentication.ResponseSent))
            {
                LoginService loginService = _authenticator.getLoginService();
                IdentityService identityService = loginService.getIdentityService();

                if (identityService != null)
                    _previousAssociation = identityService.associate(((Authentication.User)authentication).getUserIdentity());

                return authentication;
            }
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }

        return this;
    }

    @Override
    public Authentication authenticate(ServletRequest request, ServletResponse response)
    {
        try
        {
            LoginService loginService = _authenticator.getLoginService();
            IdentityService identityService = loginService.getIdentityService();

            Authentication authentication = _authenticator.validateRequest(request, response, true);
            if (authentication instanceof Authentication.User && identityService != null)
                _previousAssociation = identityService.associate(((Authentication.User)authentication).getUserIdentity());
            return authentication;
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }
        return this;
    }

    @Override
    public Authentication login(String username, Object password, ServletRequest request)
    {
        if (username == null)
            return null;

        UserIdentity identity = _authenticator.login(username, password, request);
        if (identity != null)
        {
            IdentityService identityService = _authenticator.getLoginService().getIdentityService();
            UserAuthentication authentication = new UserAuthentication("API", identity);
            if (identityService != null)
                _previousAssociation = identityService.associate(identity);
            return authentication;
        }
        return null;
    }

    @Override
    public Authentication logout(ServletRequest request)
    {
        SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
        if (security != null)
        {
            security.logout(null);
            if (_authenticator instanceof LoginAuthenticator)
            {
                ((LoginAuthenticator)_authenticator).logout(request);
                return new LoggedOutAuthentication((LoginAuthenticator)_authenticator);
            }
        }

        return Authentication.UNAUTHENTICATED;
    }

    public Object getPreviousAssociation()
    {
        return _previousAssociation;
    }

    /**
     * @param response the response
     * @return true if this response is from a deferred call to {@link #authenticate(ServletRequest)}
     */
    public static boolean isDeferred(HttpServletResponse response)
    {
        return response == __deferredResponse;
    }

    static final HttpServletResponse __deferredResponse = new HttpServletResponse()
    {
        @Override
        public void addCookie(Cookie cookie)
        {
        }

        @Override
        public void addDateHeader(String name, long date)
        {
        }

        @Override
        public void addHeader(String name, String value)
        {
        }

        @Override
        public void addIntHeader(String name, int value)
        {
        }

        @Override
        public boolean containsHeader(String name)
        {
            return false;
        }

        @Override
        public String encodeRedirectURL(String url)
        {
            return null;
        }

        @Override
        @Deprecated(since = "Servlet API 2.1")
        public String encodeRedirectUrl(String url)
        {
            return null;
        }

        @Override
        public String encodeURL(String url)
        {
            return null;
        }

        @Override
        @Deprecated(since = "Servlet API 2.1")
        public String encodeUrl(String url)
        {
            return null;
        }

        @Override
        public void sendError(int sc) throws IOException
        {
        }

        @Override
        public void sendError(int sc, String msg) throws IOException
        {
        }

        @Override
        public void sendRedirect(String location) throws IOException
        {
        }

        @Override
        public void setDateHeader(String name, long date)
        {
        }

        @Override
        public void setHeader(String name, String value)
        {
        }

        @Override
        public void setIntHeader(String name, int value)
        {
        }

        @Override
        public void setStatus(int sc)
        {
        }

        @Override
        @Deprecated(since = "Servlet API 2.1")
        public void setStatus(int sc, String sm)
        {
        }

        @Override
        public void flushBuffer() throws IOException
        {
        }

        @Override
        public int getBufferSize()
        {
            return 1024;
        }

        @Override
        public String getCharacterEncoding()
        {
            return null;
        }

        @Override
        public String getContentType()
        {
            return null;
        }

        @Override
        public Locale getLocale()
        {
            return null;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException
        {
            return __nullOut;
        }

        @Override
        public PrintWriter getWriter() throws IOException
        {
            return IO.getNullPrintWriter();
        }

        @Override
        public boolean isCommitted()
        {
            return true;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void resetBuffer()
        {
        }

        @Override
        public void setBufferSize(int size)
        {
        }

        @Override
        public void setCharacterEncoding(String charset)
        {
        }

        @Override
        public void setContentLength(int len)
        {
        }

        @Override
        public void setContentLengthLong(long len)
        {

        }

        @Override
        public void setContentType(String type)
        {
        }

        @Override
        public void setLocale(Locale loc)
        {
        }

        @Override
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

    private static ServletOutputStream __nullOut = new ServletOutputStream()
    {
        @Override
        public void write(int b) throws IOException
        {
        }

        @Override
        public void print(String s) throws IOException
        {
        }

        @Override
        public void println(String s) throws IOException
        {
        }

        @Override
        public void setWriteListener(WriteListener writeListener)
        {

        }

        @Override
        public boolean isReady()
        {
            return false;
        }
    };
}
