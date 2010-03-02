package org.eclipse.jetty.server.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Locale;
import java.util.Map;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;

public class SessionHandlerTest extends TestCase
{

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
        Assert.assertEquals(DispatcherType.REQUEST,baseRequest.getDispatcherType());

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionManager(new MockSessionManager()
        {
            public boolean isUsingCookies()
            {
                return true;
            }

            public String getSessionCookie()
            {
                return cookieName;
            }
        });
        sessionHandler.setRequestedId(baseRequest,httpRequest);

        Assert.assertEquals(sessionId,baseRequest.getRequestedSessionId());
        Assert.assertTrue(baseRequest.isRequestedSessionIdFromCookie());

    }

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
        Assert.assertEquals(DispatcherType.REQUEST,baseRequest.getDispatcherType());

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

        sessionHandler.setRequestedId(baseRequest,httpRequest);

        Assert.assertEquals(sessionId,baseRequest.getRequestedSessionId());
        Assert.assertFalse(baseRequest.isRequestedSessionIdFromCookie());
    }

    /**
     * Mock class for HttpServletRequest interface.
     */
    @SuppressWarnings("unchecked")
    class MockHttpServletRequest implements HttpServletRequest
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
    }

    /**
     * Mock class for SessionManager interface.
     */
    class MockSessionManager implements SessionManager
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

        public SessionIdManager getIdManager()
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

        public void setIdManager(SessionIdManager idManager)
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

    }

}
