//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.authentication;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.AuthenticationState.Succeeded;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FORM Authenticator.
 *
 * <p>This authenticator implements form authentication will use dispatchers to
 * the login page if the {@link #__FORM_DISPATCH} init parameter is set to true.
 * Otherwise it will redirect.</p>
 *
 * <p>The form authenticator redirects unauthenticated requests to a log page
 * which should use a form to gather username/password from the user and send them
 * to the /j_security_check URI within the context.  FormAuthentication uses
 * {@link SessionAuthentication} to wrap Authentication results so that they
 * are  associated with the session.</p>
 */
public class FormAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(FormAuthenticator.class);

    public static final String __FORM_LOGIN_PAGE = "org.eclipse.jetty.security.form_login_page";
    public static final String __FORM_ERROR_PAGE = "org.eclipse.jetty.security.form_error_page";
    public static final String __FORM_DISPATCH = "org.eclipse.jetty.security.dispatch";
    public static final String __J_URI = "org.eclipse.jetty.security.form_URI";
    public static final String __J_POST = "org.eclipse.jetty.security.form_POST";
    public static final String __J_METHOD = "org.eclipse.jetty.security.form_METHOD";
    public static final String __J_SECURITY_CHECK = "/j_security_check";
    public static final String __J_USERNAME = "j_username";
    public static final String __J_PASSWORD = "j_password";

    private String _formErrorPage;
    private String _formErrorPath;
    private String _formLoginPage;
    private String _formLoginPath;
    private boolean _dispatch;
    private boolean _alwaysSaveUri;

    public FormAuthenticator()
    {
    }

    public FormAuthenticator(String login, String error, boolean dispatch)
    {
        this();
        if (login != null)
            setLoginPage(login);
        if (error != null)
            setErrorPage(error);
        _dispatch = dispatch;
    }

    /**
     * If true, uris that cause a redirect to a login page will always
     * be remembered. If false, only the first uri that leads to a login
     * page redirect is remembered.
     * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=379909
     *
     * @param alwaysSave true to always save the uri
     */
    public void setAlwaysSaveUri(boolean alwaysSave)
    {
        _alwaysSaveUri = alwaysSave;
    }

    public boolean getAlwaysSaveUri()
    {
        return _alwaysSaveUri;
    }

    @Override
    public void setConfiguration(Configuration configuration)
    {
        super.setConfiguration(configuration);
        String login = configuration.getParameter(FormAuthenticator.__FORM_LOGIN_PAGE);
        if (login != null)
            setLoginPage(login);
        String error = configuration.getParameter(FormAuthenticator.__FORM_ERROR_PAGE);
        if (error != null)
            setErrorPage(error);
        String dispatch = configuration.getParameter(FormAuthenticator.__FORM_DISPATCH);
        _dispatch = dispatch == null ? _dispatch : Boolean.parseBoolean(dispatch);
    }

    @Override
    public String getAuthenticationType()
    {
        return Authenticator.FORM_AUTH;
    }

    private void setLoginPage(String path)
    {
        if (!path.startsWith("/"))
        {
            LOG.warn("form-login-page must start with /");
            path = "/" + path;
        }
        _formLoginPage = path;
        _formLoginPath = path;
        if (_formLoginPath.indexOf('?') > 0)
            _formLoginPath = _formLoginPath.substring(0, _formLoginPath.indexOf('?'));
    }

    private void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _formErrorPath = null;
            _formErrorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("form-error-page must start with /");
                path = "/" + path;
            }
            _formErrorPage = path;
            _formErrorPath = path;

            if (_formErrorPath.indexOf('?') > 0)
                _formErrorPath = _formErrorPath.substring(0, _formErrorPath.indexOf('?'));
        }
    }

    @Override
    public UserIdentity login(String username, Object password, Request request, Response response)
    {
        UserIdentity user = super.login(username, password, request, response);
        if (user != null)
        {
            Session session = request.getSession(true);
            AuthenticationState cached = new SessionAuthentication(getAuthenticationType(), user, password);
            session.setAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE, cached);
        }
        return user;
    }

    @Override
    public void logout(Request request, Response response)
    {
        super.logout(request, response);
        Session session = request.getSession(false);
        if (session == null)
            return;

        //clean up session
        session.removeAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
    }

    @Override
    public Request prepareRequest(Request request, AuthenticationState authenticationState)
    {
        // if this is a request resulting from a redirect after auth is complete
        // (ie its from a redirect to the original request uri) then due to
        // browser handling of 302 redirects, the method may not be the same as
        // that of the original request. Replace the method and original post
        // params (if it was a post).
        if (authenticationState instanceof Succeeded)
        {
            Session session = request.getSession(false);

            HttpURI juri = (HttpURI)session.getAttribute(__J_URI);
            HttpURI uri = request.getHttpURI();
            if ((uri.equals(juri)))
            {
                session.removeAttribute(__J_URI);

                Object post = session.removeAttribute(__J_POST);
                if (post instanceof CompletableFuture<?> futureFields)
                    FormFields.set(request, (CompletableFuture<Fields>)futureFields);

                String method = (String)session.removeAttribute(__J_METHOD);
                if (method != null && request.getMethod().equals(method))
                {
                    return new Request.Wrapper(request)
                    {
                        @Override
                        public String getMethod()
                        {
                            return method;
                        }
                    };
                }
            }
        }

        return request;
    }

    protected Fields getParameters(Request request)
    {
        try
        {
            Fields queryFields = Request.extractQueryParameters(request);
            Fields formFields = FormFields.from(request).get();
            return Fields.combine(queryFields, formFields);
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }

    protected String encodeURL(String url, Request request)
    {
        Session session = request.getSession(false);
        if (session == null)
            return url;

        return session.encodeURI(request, url, request.getHeaders().contains(HttpHeader.COOKIE));
    }

    @Override
    public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        if (isJSecurityCheck(pathInContext))
            return Constraint.Authorization.ANY_USER;
        if (isLoginOrErrorPage(pathInContext))
            return Constraint.Authorization.ALLOWED;
        return existing;
    }

    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
    {
        String pathInContext = Request.getPathInContext(request);
        boolean jSecurityCheck = isJSecurityCheck(pathInContext);

        // Handle a request for authentication.
        if (jSecurityCheck)
        {
            Fields parameters = getParameters(request);
            final String username = parameters.getValue(__J_USERNAME);
            final String password = parameters.getValue(__J_PASSWORD);

            UserIdentity user = login(username, password, request, response);
            LOG.debug("jsecuritycheck {} {}", username, user);
            if (user != null)
            {
                // Redirect to original request
                Session session = request.getSession(false);
                HttpURI savedURI = (HttpURI)session.getAttribute(__J_URI);
                String originalURI = savedURI != null ? savedURI.asString() : Request.getContextPath(request);
                if (originalURI == null)
                    originalURI = "/";
                UserAuthenticationSent formAuth = new UserAuthenticationSent(getAuthenticationType(), user);
                Response.sendRedirect(request, response, callback, encodeURL(originalURI, request), true);
                return formAuth;
            }

            // not authenticated
            if (_formErrorPage == null)
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            else
                Response.sendRedirect(request, response, callback, encodeURL(URIUtil.addPaths(request.getContext().getContextPath(), _formErrorPage), request), true);

            return AuthenticationState.SEND_FAILURE;
        }

        // Look for cached authentication
        Session session = request.getSession(false);
        AuthenticationState authenticationState = session == null ? null : (AuthenticationState)session.getAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
        if (LOG.isDebugEnabled())
            LOG.debug("auth {}", authenticationState);
        // Has authentication been revoked?
        if (authenticationState instanceof Succeeded succeeded && _loginService != null && !_loginService.validate(succeeded.getUserIdentity()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth revoked {}", authenticationState);
            session.removeAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
            authenticationState = null;
        }

        if (authenticationState != null)
            return authenticationState;

        // if we can't send challenge
        if (response.isCommitted())
        {
            LOG.debug("auth deferred {}", session == null ? null : session.getId());
            return null;
        }

        // remember the current URI
        session = (session != null ? session : request.getSession(true));
        synchronized (session)
        {
            // But only if it is not set already, or we save every uri that leads to a login form redirect
            if (session.getAttribute(__J_URI) == null || _alwaysSaveUri)
            {
                HttpURI juri = request.getHttpURI();
                session.setAttribute(__J_URI, juri.asImmutable());
                if (!HttpMethod.GET.is(request.getMethod()))
                    session.setAttribute(__J_METHOD, request.getMethod());

                if (HttpMethod.POST.is(request.getMethod()))
                {
                    try
                    {
                        CompletableFuture<Fields> futureFields = FormFields.from(request);
                        futureFields.get();
                        session.setAttribute(__J_POST, futureFields);
                    }
                    catch (ExecutionException e)
                    {
                        throw new ServerAuthException(e.getCause());
                    }
                    catch (InterruptedException e)
                    {
                        throw new ServerAuthException(e);
                    }
                }
            }
        }

        // send the challenge
        if (LOG.isDebugEnabled())
            LOG.debug("challenge {}->{}", session.getId(), _formLoginPage);
        Response.sendRedirect(request, response, callback, encodeURL(URIUtil.addPaths(request.getContext().getContextPath(), _formLoginPage), request), true);
        return AuthenticationState.CHALLENGE;
    }

    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(__J_SECURITY_CHECK);

        if (jsc < 0)
            return false;
        int e = jsc + __J_SECURITY_CHECK.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isLoginOrErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_formErrorPath) || pathInContext.equals(_formLoginPath));
    }
}
