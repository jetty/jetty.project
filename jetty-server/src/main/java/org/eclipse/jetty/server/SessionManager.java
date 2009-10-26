// ========================================================================
// Copyright (c) 1996-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.EventListener;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.component.LifeCycle;

/* --------------------------------------------------------------------- */
/**
 * Session Manager.
 * The API required to manage sessions for a servlet context.
 *
 * 
 */
public interface SessionManager extends LifeCycle
{
    /* ------------------------------------------------------------ */
    /**
     * Session cookie name.
     * Defaults to JSESSIONID, but can be set with the
     * org.eclipse.jetty.servlet.SessionCookie context init parameter.
     */
    public final static String __SessionCookieProperty = "org.eclipse.jetty.servlet.SessionCookie";
    public final static String __DefaultSessionCookie = "JSESSIONID";


    /* ------------------------------------------------------------ */
    /**
     * Session id path parameter name.
     * Defaults to jsessionid, but can be set with the
     * org.eclipse.jetty.servlet.SessionIdPathParameterName context init parameter.
     * If set to null or "none" no URL rewriting will be done.
     */
    public final static String __SessionIdPathParameterNameProperty = "org.eclipse.jetty.servlet.SessionIdPathParameterName";
    public final static String __DefaultSessionIdPathParameterName = "jsessionid";


    /* ------------------------------------------------------------ */
    /**
     * Session Domain.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the domain for session cookies. If it is not set, then
     * no domain is specified for the session cookie.
     */
    public final static String __SessionDomainProperty = "org.eclipse.jetty.servlet.SessionDomain";
    public final static String __DefaultSessionDomain = null;


    /* ------------------------------------------------------------ */
    /**
     * Session Path.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the path for the session cookie.  If it is not set, then
     * the context path is used as the path for the cookie.
     */
    public final static String __SessionPathProperty = "org.eclipse.jetty.servlet.SessionPath";

    /* ------------------------------------------------------------ */
    /**
     * Session Max Age.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the max age for the session cookie.  If it is not set, then
     * a max age of -1 is used.
     */
    public final static String __MaxAgeProperty = "org.eclipse.jetty.servlet.MaxAge";

    /* ------------------------------------------------------------ */
    /**
     * Returns the <code>HttpSession</code> with the given session id
     *
     * @param id the session id
     * @return the <code>HttpSession</code> with the corresponding id or null if no session with the given id exists
     */
    public HttpSession getHttpSession(String id);

    /* ------------------------------------------------------------ */
    /**
     * Creates a new <code>HttpSession</code>.
     *
     * @param request the HttpServletRequest containing the requested session id
     * @return the new <code>HttpSession</code>
     */
    public HttpSession newHttpSession(HttpServletRequest request);

    /* ------------------------------------------------------------ */
    /**
     * @return true if session cookies should be secure
     */
    public boolean getSecureCookies();

    /* ------------------------------------------------------------ */
    /**
     * @return true if session cookies should be HTTP-only (Microsoft extension)
     * @see Cookie#isHttpOnly()
     */
    public boolean getHttpOnly();

    /* ------------------------------------------------------------ */
    /**
     * @return the max period of inactivity, after which the session is invalidated, in seconds.
     * @see #setMaxInactiveInterval(int)
     */
    public int getMaxInactiveInterval();

    /* ------------------------------------------------------------ */
    /**
     * Sets the max period of inactivity, after which the session is invalidated, in seconds.
     *
     * @param seconds the max inactivity period, in seconds.
     * @see #getMaxInactiveInterval()
     */
    public void setMaxInactiveInterval(int seconds);

    /* ------------------------------------------------------------ */
    /**
     * Sets the {@link SessionHandler}.
     *
     * @param handler the <code>SessionHandler</code> object
     */
    public void setSessionHandler(SessionHandler handler);

    /* ------------------------------------------------------------ */
    /**
     * Adds an event listener for session-related events.
     *
     * @param listener the session event listener to add
     *                 Individual SessionManagers implementations may accept arbitrary listener types,
     *                 but they are expected to at least handle HttpSessionActivationListener,
     *                 HttpSessionAttributeListener, HttpSessionBindingListener and HttpSessionListener.
     * @see #removeEventListener(EventListener)
     */
    public void addEventListener(EventListener listener);

    /* ------------------------------------------------------------ */
    /**
     * Removes an event listener for for session-related events.
     *
     * @param listener the session event listener to remove
     * @see #addEventListener(EventListener)
     */
    public void removeEventListener(EventListener listener);

    /* ------------------------------------------------------------ */
    /**
     * Removes all event listeners for session-related events.
     *
     * @see #removeEventListener(EventListener)
     */
    public void clearEventListeners();

    /* ------------------------------------------------------------ */
    /**
     * Gets a Cookie for a session.
     *
     * @param session         the session to which the cookie should refer.
     * @param contextPath     the context to which the cookie should be linked.
     *                        The client will only send the cookie value when requesting resources under this path.
     * @param requestIsSecure whether the client is accessing the server over a secure protocol (i.e. HTTPS).
     * @return if this <code>SessionManager</code> uses cookies, then this method will return a new
     *         {@link Cookie cookie object} that should be set on the client in order to link future HTTP requests
     *         with the <code>session</code>. If cookies are not in use, this method returns <code>null</code>.
     */
    public HttpCookie getSessionCookie(HttpSession session, String contextPath, boolean requestIsSecure);

    /* ------------------------------------------------------------ */
    /**
     * @return the cross context session id manager.
     * @see #setIdManager(SessionIdManager)
     */
    public SessionIdManager getIdManager();

    /* ------------------------------------------------------------ */
    /**
     * @return the cross context session id manager.
     * @deprecated use {@link #getIdManager()}
     */
    @Deprecated
    public SessionIdManager getMetaManager();

    /* ------------------------------------------------------------ */
    /**
     * Sets the cross context session id manager
     *
     * @param idManager the cross context session id manager.
     * @see #getIdManager()
     */
    public void setIdManager(SessionIdManager idManager);

    /* ------------------------------------------------------------ */
    /**
     * @param session the session to test for validity
     * @return whether the given session is valid, that is, it has not been invalidated.
     */
    public boolean isValid(HttpSession session);

    /* ------------------------------------------------------------ */
    /**
     * @param session the session object
     * @return the unique id of the session within the cluster, extended with an optional node id.
     * @see #getClusterId(HttpSession)
     */
    public String getNodeId(HttpSession session);

    /* ------------------------------------------------------------ */
    /**
     * @param session the session object
     * @return the unique id of the session within the cluster (without a node id extension)
     * @see #getNodeId(HttpSession)
     */
    public String getClusterId(HttpSession session);

    /* ------------------------------------------------------------ */
    /**
     * Called by the {@link SessionHandler} when a session is first accessed by a request.
     *
     * @param session the session object
     * @param secure  whether the request is secure or not
     * @return the session cookie. If not null, this cookie should be set on the response to either migrate
     *         the session or to refresh a session cookie that may expire.
     * @see #complete(HttpSession)
     */
    public HttpCookie access(HttpSession session, boolean secure);

    /* ------------------------------------------------------------ */
    /**
     * Called by the {@link SessionHandler} when a session is last accessed by a request.
     *
     * @param session the session object
     * @see #access(HttpSession, boolean)
     */
    public void complete(HttpSession session);

    /**
     * Sets the session cookie name.
     * @param cookieName the session cookie name
     * @see #getSessionCookie()
     */
    public void setSessionCookie(String cookieName);

    /**
     * @return the session cookie name, by default "JSESSIONID".
     * @see #setSessionCookie(String)
     */
    public String getSessionCookie();

    /**
     * Sets the session id URL path parameter name.
     *
     * @param parameterName the URL path parameter name for session id URL rewriting (null or "none" for no rewriting).
     * @see #getSessionIdPathParameterName()
     * @see #getSessionIdPathParameterNamePrefix()
     */
    public void setSessionIdPathParameterName(String parameterName);

    /**
     * @return the URL path parameter name for session id URL rewriting, by default "jsessionid".
     * @see #setSessionIdPathParameterName(String)
     */
    public String getSessionIdPathParameterName();

    /**
     * @return a formatted version of {@link #getSessionIdPathParameterName()}, by default
     *         ";" + sessionIdParameterName + "=", for easier lookup in URL strings.
     * @see #getSessionIdPathParameterName()
     */
    public String getSessionIdPathParameterNamePrefix();

    /**
     * Sets the domain to set on the session cookie
     * @param domain the domain to set on the session cookie
     * @see #getSessionDomain()
     */
    public void setSessionDomain(String domain);

    /**
     * @return the domain to set on the session cookie
     * @see #setSessionDomain(String)
     */
    public String getSessionDomain();

    /**
     * Sets the path to set on the session cookie
     * @param path the path to set on the session cookie
     * @see #getSessionPath()
     */
    public void setSessionPath(String path);

    /**
     * @return the path to set on the session cookie
     * @see #setSessionPath(String)
     */
    public String getSessionPath();

    /**
     * Sets the max age to set on the session cookie, in seconds
     * @param maxCookieAge the max age to set on the session cookie, in seconds
     * @see #getMaxCookieAge()
     */
    public void setMaxCookieAge(int maxCookieAge);

    /**
     * @return the max age to set on the session cookie, in seconds
     * @see #setMaxCookieAge(int)
     */
    public int getMaxCookieAge();

    /**
     * @return whether the session management is handled via cookies.
     */
    public boolean isUsingCookies();
}
