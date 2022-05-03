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

package org.eclipse.jetty.ee9.security.openid;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.Authentication.User;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.nested.Response;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.ee9.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.ee9.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.ee9.security.authentication.SessionAuthentication;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.security.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Implements authentication using OpenId Connect on top of OAuth 2.0.
 *
 * <p>The OpenIdAuthenticator redirects unauthenticated requests to the OpenID Connect Provider. The End-User is
 * eventually redirected back with an Authorization Code to the path set by {@link #setRedirectPath(String)} within the context.
 * The Authorization Code is then used to authenticate the user through the {@link OpenIdCredentials} and {@link OpenIdLoginService}.
 * </p>
 * <p>
 * Once a user is authenticated the OpenID Claims can be retrieved through an attribute on the session with the key {@link #CLAIMS}.
 * The full response containing the OAuth 2.0 Access Token can be obtained with the session attribute {@link #RESPONSE}.
 * </p>
 * <p>{@link SessionAuthentication} is then used to wrap Authentication results so that they are associated with the session.</p>
 */
public class OpenIdAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenIdAuthenticator.class);

    public static final String CLAIMS = "org.eclipse.jetty.security.openid.claims";
    public static final String RESPONSE = "org.eclipse.jetty.security.openid.response";
    public static final String ISSUER = "org.eclipse.jetty.security.openid.issuer";
    public static final String REDIRECT_PATH = "org.eclipse.jetty.security.openid.redirect_path";
    public static final String ERROR_PAGE = "org.eclipse.jetty.security.openid.error_page";
    public static final String J_URI = "org.eclipse.jetty.security.openid.URI";
    public static final String J_POST = "org.eclipse.jetty.security.openid.POST";
    public static final String J_METHOD = "org.eclipse.jetty.security.openid.METHOD";
    public static final String J_SECURITY_CHECK = "/j_security_check";
    public static final String ERROR_PARAMETER = "error_description_jetty";
    private static final String CSRF_MAP = "org.eclipse.jetty.security.openid.csrf_map";

    @Deprecated
    public static final String CSRF_TOKEN = "org.eclipse.jetty.security.openid.csrf_token";

    private final SecureRandom _secureRandom = new SecureRandom();
    private OpenIdConfiguration _openIdConfiguration;
    private String _redirectPath;
    private String _errorPage;
    private String _errorPath;
    private String _errorQuery;
    private boolean _alwaysSaveUri;

    public OpenIdAuthenticator()
    {
        this(null, J_SECURITY_CHECK, null);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration)
    {
        this(configuration, J_SECURITY_CHECK, null);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration, String errorPage)
    {
        this(configuration, J_SECURITY_CHECK, errorPage);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration, String redirectPath, String errorPage)
    {
        _openIdConfiguration = configuration;
        setRedirectPath(redirectPath);
        if (errorPage != null)
            setErrorPage(errorPage);
    }

    @Override
    public void setConfiguration(Authenticator.AuthConfiguration authConfig)
    {
        if (_openIdConfiguration == null)
        {
            LoginService loginService = authConfig.getLoginService();
            if (!(loginService instanceof OpenIdLoginService))
                throw new IllegalArgumentException("invalid LoginService " + loginService);
            this._openIdConfiguration = ((OpenIdLoginService)loginService).getConfiguration();
        }

        String redirectPath = authConfig.getInitParameter(REDIRECT_PATH);
        if (redirectPath != null)
            _redirectPath = redirectPath;

        String error = authConfig.getInitParameter(ERROR_PAGE);
        if (error != null)
            setErrorPage(error);

        super.setConfiguration(new OpenIdAuthConfiguration(_openIdConfiguration, authConfig));
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__OPENID_AUTH;
    }

    @Deprecated
    public void setAlwaysSaveUri(boolean alwaysSave)
    {
        _alwaysSaveUri = alwaysSave;
    }

    @Deprecated
    public boolean isAlwaysSaveUri()
    {
        return _alwaysSaveUri;
    }

    public void setRedirectPath(String redirectPath)
    {
        if (redirectPath == null)
        {
            LOG.warn("redirect path must not be null, defaulting to " + J_SECURITY_CHECK);
            redirectPath = J_SECURITY_CHECK;
        }
        else if (!redirectPath.startsWith("/"))
        {
            LOG.warn("redirect path must start with /");
            redirectPath = "/" + redirectPath;
        }

        _redirectPath = redirectPath;
    }

    public void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _errorPath = null;
            _errorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("error-page must start with /");
                path = "/" + path;
            }
            _errorPage = path;
            _errorPath = path;
            _errorQuery = "";

            int queryIndex = _errorPath.indexOf('?');
            if (queryIndex > 0)
            {
                _errorPath = _errorPage.substring(0, queryIndex);
                _errorQuery = _errorPage.substring(queryIndex + 1);
            }
        }
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("login {} {} {}", username, credentials, request);

        UserIdentity user = super.login(username, credentials, request);
        if (user != null)
        {
            HttpSession session = ((HttpServletRequest)request).getSession();
            Authentication cached = new SessionAuthentication(getAuthMethod(), user, credentials);
            synchronized (session)
            {
                session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, cached);
                session.setAttribute(CLAIMS, ((OpenIdCredentials)credentials).getClaims());
                session.setAttribute(RESPONSE, ((OpenIdCredentials)credentials).getResponse());
                session.setAttribute(ISSUER, _openIdConfiguration.getIssuer());
            }
        }
        return user;
    }

    @Override
    public void logout(ServletRequest request)
    {
        super.logout(request);
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);

        if (session == null)
            return;

        synchronized (session)
        {
            session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
            session.removeAttribute(CLAIMS);
            session.removeAttribute(RESPONSE);
        }
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        //if this is a request resulting from a redirect after auth is complete
        //(ie its from a redirect to the original request uri) then due to
        //browser handling of 302 redirects, the method may not be the same as
        //that of the original request. Replace the method and original post
        //params (if it was a post).
        //
        //See Servlet Spec 3.1 sec 13.6.3
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);
        if (session == null)
            return; //not authenticated yet

        String juri;
        String method;
        synchronized (session)
        {
            if (session.getAttribute(SessionAuthentication.__J_AUTHENTICATED) == null)
                return; //not authenticated yet

            juri = (String)session.getAttribute(J_URI);
            if (juri == null || juri.length() == 0)
                return; //no original uri saved

            method = (String)session.getAttribute(J_METHOD);
            if (method == null || method.length() == 0)
                return; //didn't save original request method
        }

        StringBuffer buf = httpRequest.getRequestURL();
        if (httpRequest.getQueryString() != null)
            buf.append("?").append(httpRequest.getQueryString());

        if (!juri.equals(buf.toString()))
            return; //this request is not for the same url as the original

        // Restore the original request's method on this request.
        if (LOG.isDebugEnabled())
            LOG.debug("Restoring original method {} for {} with method {}", method, juri, httpRequest.getMethod());
        Request baseRequest = Objects.requireNonNull(Request.getBaseRequest(request));
        baseRequest.setMethod(method);
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        final HttpServletRequest request = (HttpServletRequest)req;
        final HttpServletResponse response = (HttpServletResponse)res;
        final Request baseRequest = Objects.requireNonNull(Request.getBaseRequest(request));
        final Response baseResponse = baseRequest.getResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("validateRequest({},{},{})", req, res, mandatory);

        String uri = request.getRequestURI();
        if (uri == null)
            uri = URIUtil.SLASH;

        mandatory |= isJSecurityCheck(uri);
        if (!mandatory)
            return new DeferredAuthentication(this);

        if (isErrorPage(baseRequest.getPathInContext()) && !DeferredAuthentication.isDeferred(response))
            return new DeferredAuthentication(this);

        try
        {
            // Get the Session.
            HttpSession session = request.getSession();
            if (request.isRequestedSessionIdFromURL())
            {
                sendError(request, response, "Session ID must be a cookie to support OpenID authentication");
                return Authentication.SEND_FAILURE;
            }

            // Handle a request for authentication.
            if (isJSecurityCheck(uri))
            {
                String authCode = request.getParameter("code");
                if (authCode == null)
                {
                    sendError(request, response, "auth failed: no code parameter");
                    return Authentication.SEND_FAILURE;
                }

                String state = request.getParameter("state");
                if (state == null)
                {
                    sendError(request, response, "auth failed: no state parameter");
                    return Authentication.SEND_FAILURE;
                }

                // Verify anti-forgery state token.
                UriRedirectInfo uriRedirectInfo;
                synchronized (session)
                {
                    uriRedirectInfo = removeAndClearCsrfMap(session, state);
                }
                if (uriRedirectInfo == null)
                {
                    sendError(request, response, "auth failed: invalid state parameter");
                    return Authentication.SEND_FAILURE;
                }

                // Attempt to login with the provided authCode.
                OpenIdCredentials credentials = new OpenIdCredentials(authCode, getRedirectUri(request));
                UserIdentity user = login(null, credentials, request);
                if (user == null)
                {
                    sendError(request, response, null);
                    return Authentication.SEND_FAILURE;
                }

                OpenIdAuthentication openIdAuth = new OpenIdAuthentication(getAuthMethod(), user);
                if (LOG.isDebugEnabled())
                    LOG.debug("authenticated {}->{}", openIdAuth, uriRedirectInfo.getUri());

                // Save redirect info in session so original request can be restored after redirect.
                synchronized (session)
                {
                    session.setAttribute(J_URI, uriRedirectInfo.getUri());
                    session.setAttribute(J_METHOD, uriRedirectInfo.getMethod());
                    session.setAttribute(J_POST, uriRedirectInfo.getFormParameters());
                }

                // Redirect to the original URI.
                response.setContentLength(0);
                baseResponse.sendRedirect(uriRedirectInfo.getUri(), true);
                return openIdAuth;
            }

            // Look for cached authentication in the Session.
            Authentication authentication = (Authentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
            if (authentication != null)
            {
                // Has authentication been revoked?
                if (authentication instanceof Authentication.User && _loginService != null &&
                    !_loginService.validate(((Authentication.User)authentication).getUserIdentity()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth revoked {}", authentication);
                    synchronized (session)
                    {
                        session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
                    }
                }
                else
                {
                    synchronized (session)
                    {
                        String jUri = (String)session.getAttribute(J_URI);
                        if (jUri != null)
                        {
                            // Check if the request is for the same url as the original and restore params if it was a post.
                            if (LOG.isDebugEnabled())
                                LOG.debug("auth retry {}->{}", authentication, jUri);
                            StringBuffer buf = request.getRequestURL();
                            if (request.getQueryString() != null)
                                buf.append("?").append(request.getQueryString());

                            if (jUri.equals(buf.toString()))
                            {
                                @SuppressWarnings("unchecked")
                                MultiMap<String> jPost = (MultiMap<String>)session.getAttribute(J_POST);
                                if (jPost != null)
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("auth rePOST {}->{}", authentication, jUri);
                                    baseRequest.setContentParameters(jPost);
                                }
                                session.removeAttribute(J_URI);
                                session.removeAttribute(J_METHOD);
                                session.removeAttribute(J_POST);
                            }
                        }
                    }
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("auth {}", authentication);
                return authentication;
            }

            // If we can't send challenge.
            if (DeferredAuthentication.isDeferred(response))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("auth deferred {}", session.getId());
                return Authentication.UNAUTHENTICATED;
            }

            // Send the the challenge.
            String challengeUri = getChallengeUri(baseRequest);
            if (LOG.isDebugEnabled())
                LOG.debug("challenge {}->{}", session.getId(), challengeUri);
            baseResponse.sendRedirect(challengeUri, true);

            return Authentication.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    /**
     * Report an error case either by redirecting to the error page if it is defined, otherwise sending a 403 response.
     * If the message parameter is not null, a query parameter with a key of {@link #ERROR_PARAMETER} and value of the error
     * message will be logged and added to the error redirect URI if the error page is defined.
     * @param request the request.
     * @param response the response.
     * @param message the reason for the error or null.
     * @throws IOException if sending the error fails for any reason.
     */
    private void sendError(HttpServletRequest request, HttpServletResponse response, String message) throws IOException
    {
        final Request baseRequest = Request.getBaseRequest(request);
        final Response baseResponse = Objects.requireNonNull(baseRequest).getResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("OpenId authentication FAILED: {}", message);

        if (_errorPage == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth failed 403");
            if (response != null)
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth failed {}", _errorPage);

            String redirectUri = URIUtil.addPaths(request.getContextPath(), _errorPage);
            if (message != null)
            {
                String query = URIUtil.addQueries(ERROR_PARAMETER + "=" + UrlEncoded.encodeString(message), _errorQuery);
                redirectUri = URIUtil.addPathQuery(URIUtil.addPaths(request.getContextPath(), _errorPath), query);
            }

            baseResponse.sendRedirect(redirectUri, true);
        }
    }

    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(_redirectPath);

        if (jsc < 0)
            return false;
        int e = jsc + _redirectPath.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_errorPath));
    }

    private String getRedirectUri(HttpServletRequest request)
    {
        final StringBuffer redirectUri = new StringBuffer(128);
        URIUtil.appendSchemeHostPort(redirectUri, request.getScheme(),
            request.getServerName(), request.getServerPort());
        redirectUri.append(request.getContextPath());
        redirectUri.append(_redirectPath);
        return redirectUri.toString();
    }

    protected String getChallengeUri(Request request)
    {
        HttpSession session = request.getSession();
        String antiForgeryToken;
        synchronized (session)
        {
            Map<String, UriRedirectInfo> csrfMap = ensureCsrfMap(session);
            antiForgeryToken = new BigInteger(130, _secureRandom).toString(32);
            csrfMap.put(antiForgeryToken, new UriRedirectInfo(request));
        }

        // any custom scopes requested from configuration
        StringBuilder scopes = new StringBuilder();
        for (String s : _openIdConfiguration.getScopes())
        {
            scopes.append(" ").append(s);
        }

        return _openIdConfiguration.getAuthEndpoint() +
            "?client_id=" + UrlEncoded.encodeString(_openIdConfiguration.getClientId(), StandardCharsets.UTF_8) +
            "&redirect_uri=" + UrlEncoded.encodeString(getRedirectUri(request), StandardCharsets.UTF_8) +
            "&scope=openid" + UrlEncoded.encodeString(scopes.toString(), StandardCharsets.UTF_8) +
            "&state=" + antiForgeryToken +
            "&response_type=code";
    }

    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser)
    {
        return req.isSecure();
    }

    private UriRedirectInfo removeAndClearCsrfMap(HttpSession session, String csrf)
    {
        @SuppressWarnings("unchecked")
        Map<String, UriRedirectInfo> csrfMap = (Map<String, UriRedirectInfo>)session.getAttribute(CSRF_MAP);
        if (csrfMap == null)
            return null;

        UriRedirectInfo uriRedirectInfo = csrfMap.get(csrf);
        csrfMap.clear();
        return uriRedirectInfo;
    }

    private Map<String, UriRedirectInfo> ensureCsrfMap(HttpSession session)
    {
        @SuppressWarnings("unchecked")
        Map<String, UriRedirectInfo> csrfMap = (Map<String, UriRedirectInfo>)session.getAttribute(CSRF_MAP);
        if (csrfMap == null)
        {
            csrfMap = new MRUMap(64);
            session.setAttribute(CSRF_MAP, csrfMap);
        }
        return csrfMap;
    }

    private static class MRUMap extends LinkedHashMap<String, UriRedirectInfo>
    {
        private static final long serialVersionUID = 5375723072014233L;

        private final int _size;

        private MRUMap(int size)
        {
            _size = size;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, UriRedirectInfo> eldest)
        {
            return size() > _size;
        }
    }

    private static class UriRedirectInfo implements Serializable
    {
        private static final long serialVersionUID = 139567755844461433L;

        private final String _uri;
        private final String _method;
        private final MultiMap<String> _formParameters;

        public UriRedirectInfo(Request request)
        {
            _uri = request.getRequestURI();
            _method = request.getMethod();

            if (MimeTypes.Type.FORM_ENCODED.is(request.getContentType()) && HttpMethod.POST.is(request.getMethod()))
            {
                MultiMap<String> formParameters = new MultiMap<>();
                request.extractFormParameters(formParameters);
                _formParameters = formParameters;
            }
            else
            {
                _formParameters = null;
            }
        }

        public String getUri()
        {
            return _uri;
        }

        public String getMethod()
        {
            return _method;
        }

        public MultiMap<String> getFormParameters()
        {
            return _formParameters;
        }
    }

    /**
     * This Authentication represents a just completed OpenId Connect authentication.
     * Subsequent requests from the same user are authenticated by the presents
     * of a {@link SessionAuthentication} instance in their session.
     */
    public static class OpenIdAuthentication extends UserAuthentication implements Authentication.ResponseSent
    {
        public OpenIdAuthentication(String method, UserIdentity userIdentity)
        {
            super(method, userIdentity);
        }

        @Override
        public String toString()
        {
            return "OpenId" + super.toString();
        }
    }
}
