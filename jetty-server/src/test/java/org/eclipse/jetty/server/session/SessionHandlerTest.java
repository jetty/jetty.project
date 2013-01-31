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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Collection;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.AsyncContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.junit.Test;

public class SessionHandlerTest
{
    @Test
    public void testRequestedIdFromCookies()
    {
        final String cookieName = "SessionId";
        final String sessionId = "1234.host";
        HttpServletRequest httpRequest = new MockHttpServletRequest()
        {
            public Cookie[] getCookies()
            {
                return new Cookie[]
                { new Cookie(cookieName,sessionId) };
            }
        };

        Request baseRequest = new Request();
        baseRequest.setDispatcherType(DispatcherType.REQUEST);
        assertEquals(DispatcherType.REQUEST,baseRequest.getDispatcherType());

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionManager(new MockSessionManager()
        {
 
            
            public SessionCookieConfig getSessionCookieConfig()
            {
                return new SessionCookieConfig()
                {

                    public String getComment()
                    {
                        // TODO Auto-generated method stub
                        return null;
                    }

                    public String getDomain()
                    {
                        // TODO Auto-generated method stub
                        return null;
                    }

                    public int getMaxAge()
                    {
                        // TODO Auto-generated method stub
                        return 0;
                    }

                    public String getName()
                    {
                        return cookieName;
                    }

                    public String getPath()
                    {
                        // TODO Auto-generated method stub
                        return null;
                    }

                    public boolean isHttpOnly()
                    {
                        // TODO Auto-generated method stub
                        return false;
                    }

                    public boolean isSecure()
                    {
                        // TODO Auto-generated method stub
                        return false;
                    }

                    public void setComment(String comment)
                    {
                        // TODO Auto-generated method stub
                        
                    }

                    public void setDomain(String domain)
                    {
                        // TODO Auto-generated method stub
                        
                    }

                    public void setHttpOnly(boolean httpOnly)
                    {
                        // TODO Auto-generated method stub
                        
                    }

                    public void setMaxAge(int maxAge)
                    {
                        // TODO Auto-generated method stub
                        
                    }

                    public void setName(String name)
                    {
                        // TODO Auto-generated method stub
                        
                    }

                    public void setPath(String path)
                    {
                        // TODO Auto-generated method stub
                        
                    }

                    public void setSecure(boolean secure)
                    {
                        // TODO Auto-generated method stub
                        
                    }
                    
                };
            }
            public boolean isUsingCookies()
            {
                return true;
            }

            public String getSessionCookie()
            {
                return cookieName;
            }
        });
        sessionHandler.checkRequestedSessionId(baseRequest,httpRequest);

        assertEquals(sessionId,baseRequest.getRequestedSessionId());
        assertTrue(baseRequest.isRequestedSessionIdFromCookie());
    }

    @Test
    public void testRequestedIdFromURI()
    {
        final String parameterName = "sessionid";
        final String sessionId = "1234.host";
        HttpServletRequest httpRequest = new MockHttpServletRequest()
        {
            @Override
            public String getRequestURI()
            {
                return "http://www.foo.net/app/action.do;" + parameterName + "=" + sessionId + ";p1=abc;p2=def";
            }
        };

        Request baseRequest = new Request();
        baseRequest.setDispatcherType(DispatcherType.REQUEST);
        assertEquals(DispatcherType.REQUEST,baseRequest.getDispatcherType());

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionManager(new MockSessionManager()
        {       

            @Override
            public String getSessionIdPathParameterName()
            {
                return parameterName;
            }

            @Override
            public String getSessionIdPathParameterNamePrefix()
            {
                return ";"+parameterName+"=";
            }
        });

        sessionHandler.checkRequestedSessionId(baseRequest,httpRequest);

        assertEquals(sessionId,baseRequest.getRequestedSessionId());
        assertFalse(baseRequest.isRequestedSessionIdFromCookie());
    }

    /**
     * Mock class for HttpServletRequest interface.
     */
    @SuppressWarnings("unchecked")
    private class MockHttpServletRequest implements HttpServletRequest
    {
        public String getRequestURI()
        {
            return null;
        }

        public Cookie[] getCookies()
        {
            return null;
        }

        public String getAuthType()
        {
            return null;
        }

        public String getContextPath()
        {
            return null;
        }

        public long getDateHeader(String name)
        {
            return 0;
        }

        public String getHeader(String name)
        {
            return null;
        }

        public Enumeration getHeaderNames()
        {
            return null;
        }

        public Enumeration getHeaders(String name)
        {
            return null;
        }

        public int getIntHeader(String name)
        {
            return 0;
        }

        public String getMethod()
        {
            return null;
        }

        public String getPathInfo()
        {
            return null;
        }

        public String getPathTranslated()
        {
            return null;
        }

        public String getQueryString()
        {
            return null;
        }

        public String getRemoteUser()
        {
            return null;
        }

        public StringBuffer getRequestURL()
        {
            return null;
        }

        public String getRequestedSessionId()
        {
            return null;
        }

        public String getServletPath()
        {
            return null;
        }

        public HttpSession getSession()
        {
            return null;
        }

        public HttpSession getSession(boolean create)
        {
            return null;
        }

        public Principal getUserPrincipal()
        {
            return null;
        }

        public boolean isRequestedSessionIdFromCookie()
        {
            return false;
        }

        public boolean isRequestedSessionIdFromURL()
        {
            return false;
        }

        public boolean isRequestedSessionIdFromUrl()
        {
            return false;
        }

        public boolean isRequestedSessionIdValid()
        {
            return false;
        }

        public boolean isUserInRole(String role)
        {
            return false;
        }

        public Object getAttribute(String name)
        {
            return null;
        }

        public Enumeration getAttributeNames()
        {
            return null;
        }

        public String getCharacterEncoding()
        {
            return null;
        }

        public int getContentLength()
        {
            return 0;
        }

        public String getContentType()
        {
            return null;
        }

        public ServletInputStream getInputStream() throws IOException
        {
            return null;
        }

        public String getLocalAddr()
        {
            return null;
        }

        public String getLocalName()
        {
            return null;
        }

        public int getLocalPort()
        {
            return 0;
        }

        public Locale getLocale()
        {
            return null;
        }

        public Enumeration getLocales()
        {
            return null;
        }

        public String getParameter(String name)
        {
            return null;
        }

        public Map getParameterMap()
        {
            return null;
        }

        public Enumeration getParameterNames()
        {
            return null;
        }

        public String[] getParameterValues(String name)
        {
            return null;
        }

        public String getProtocol()
        {
            return null;
        }

        public BufferedReader getReader() throws IOException
        {
            return null;
        }

        public String getRealPath(String path)
        {
            return null;
        }

        public String getRemoteAddr()
        {
            return null;
        }

        public String getRemoteHost()
        {
            return null;
        }

        public int getRemotePort()
        {
            return 0;
        }

        public RequestDispatcher getRequestDispatcher(String path)
        {
            return null;
        }

        public String getScheme()
        {
            return null;
        }

        public String getServerName()
        {
            return null;
        }

        public int getServerPort()
        {
            return 0;
        }

        public boolean isSecure()
        {
            return false;
        }

        public void removeAttribute(String name)
        {
        }

        public void setAttribute(String name, Object o)
        {
        }

        public void setCharacterEncoding(String env) throws UnsupportedEncodingException
        {
        }

        /** 
         * @see javax.servlet.http.HttpServletRequest#authenticate(javax.servlet.http.HttpServletResponse)
         */
        public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
        {
            // TODO Auto-generated method stub
            return false;
        }

        /** 
         * @see javax.servlet.http.HttpServletRequest#getPart(java.lang.String)
         */
        public Part getPart(String name) throws IOException, ServletException
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see javax.servlet.http.HttpServletRequest#getParts()
         */
        public Collection<Part> getParts() throws IOException, ServletException
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see javax.servlet.http.HttpServletRequest#login(java.lang.String, java.lang.String)
         */
        public void login(String username, String password) throws ServletException
        {
            // TODO Auto-generated method stub
            
        }

        /** 
         * @see javax.servlet.http.HttpServletRequest#logout()
         */
        public void logout() throws ServletException
        {
            // TODO Auto-generated method stub
            
        }

        /** 
         * @see javax.servlet.ServletRequest#getAsyncContext()
         */
        public AsyncContext getAsyncContext()
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see javax.servlet.ServletRequest#getDispatcherType()
         */
        public DispatcherType getDispatcherType()
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see javax.servlet.ServletRequest#getServletContext()
         */
        public ServletContext getServletContext()
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see javax.servlet.ServletRequest#isAsyncStarted()
         */
        public boolean isAsyncStarted()
        {
            // TODO Auto-generated method stub
            return false;
        }

        /** 
         * @see javax.servlet.ServletRequest#isAsyncSupported()
         */
        public boolean isAsyncSupported()
        {
            // TODO Auto-generated method stub
            return false;
        }

        /** 
         * @see javax.servlet.ServletRequest#startAsync()
         */
        public AsyncContext startAsync() throws IllegalStateException
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see javax.servlet.ServletRequest#startAsync(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
         */
        public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
        {
            // TODO Auto-generated method stub
            return null;
        }
    }

    /**
     * Mock class for SessionManager interface.
     */
    private class MockSessionManager implements SessionManager
    {
        public HttpCookie access(HttpSession session, boolean secure)
        {
            return null;
        }

        public void addEventListener(EventListener listener)
        {
        }

        public void clearEventListeners()
        {
        }

        public void complete(HttpSession session)
        {
        }

        public String getClusterId(HttpSession session)
        {
            return null;
        }

        public boolean getHttpOnly()
        {
            return false;
        }

        public HttpSession getHttpSession(String id)
        {
            return null;
        }

        public SessionIdManager getSessionIdManager()
        {
            return null;
        }

        public int getMaxCookieAge()
        {
            return 0;
        }

        public int getMaxInactiveInterval()
        {
            return 0;
        }

        public SessionIdManager getMetaManager()
        {
            return null;
        }

        public String getNodeId(HttpSession session)
        {
            return null;
        }

        public boolean getSecureCookies()
        {
            return false;
        }

        public HttpCookie getSessionCookie(HttpSession session, String contextPath, boolean requestIsSecure)
        {
            return null;
        }

        public String getSessionCookie()
        {
            return null;
        }

        public String getSessionDomain()
        {
            return null;
        }

        public String getSessionIdPathParameterName()
        {
            return null;
        }

        public String getSessionIdPathParameterNamePrefix()
        {
            return null;
        }

        public String getSessionPath()
        {
            return null;
        }

        public boolean isUsingCookies()
        {
            return false;
        }

        public boolean isValid(HttpSession session)
        {
            return false;
        }

        public HttpSession newHttpSession(HttpServletRequest request)
        {
            return null;
        }

        public void removeEventListener(EventListener listener)
        {
        }

        public void setSessionIdManager(SessionIdManager idManager)
        {
        }

        public void setMaxCookieAge(int maxCookieAge)
        {
        }

        public void setMaxInactiveInterval(int seconds)
        {
        }

        public void setSessionCookie(String cookieName)
        {
        }

        public void setSessionDomain(String domain)
        {
        }

        public void setSessionHandler(SessionHandler handler)
        {
        }

        public void setSessionIdPathParameterName(String parameterName)
        {
        }

        public void setSessionPath(String path)
        {
        }

        public void addLifeCycleListener(Listener listener)
        {
        }

        public boolean isFailed()
        {
            return false;
        }

        public boolean isRunning()
        {
            return false;
        }

        public boolean isStarted()
        {
            return false;
        }

        public boolean isStarting()
        {
            return false;
        }

        public boolean isStopped()
        {
            return false;
        }

        public boolean isStopping()
        {
            return false;
        }

        public void removeLifeCycleListener(Listener listener)
        {
        }

        public void start() throws Exception
        {
        }

        public void stop() throws Exception
        {
        }

        /** 
         * @see org.eclipse.jetty.server.SessionManager#getDefaultSessionTrackingModes()
         */
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.SessionManager#getEffectiveSessionTrackingModes()
         */
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.SessionManager#getSessionCookieConfig()
         */
        public SessionCookieConfig getSessionCookieConfig()
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.SessionManager#isUsingURLs()
         */
        public boolean isUsingURLs()
        {
            // TODO Auto-generated method stub
            return false;
        }

        /** 
         * @see org.eclipse.jetty.server.SessionManager#setSessionTrackingModes(java.util.Set)
         */
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
            // TODO Auto-generated method stub
            
        }

        private boolean _checkRemote=false;

        public boolean isCheckingRemoteSessionIdEncoding()
        {
            return _checkRemote;
        }

        public void setCheckingRemoteSessionIdEncoding(boolean remote)
        {
            _checkRemote=remote;
        }

        public void changeSessionIdOnAuthentication(HttpServletRequest request, HttpServletResponse response)
        {
        }
    }
}
