package org.eclipse.jetty.security;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiAuthenticator.class);
    public static final String LOGIN_PATH_PARAM = "org.eclipse.jetty.security.multi.login_path";

    private static final String AUTH_STATE_ATTR = MultiAuthState.class.getName();
    private final DefaultAuthenticator _defaultAuthenticator = new DefaultAuthenticator();
    private final PathMappings<Authenticator> _authenticatorsMappings = new PathMappings<>();
    private String _loginPath;
    private boolean _dispatch;

    public void addAuthenticator(String pathSpec, Authenticator authenticator)
    {
        _authenticatorsMappings.put(pathSpec, authenticator);
    }

    @Override
    public void setConfiguration(Configuration configuration)
    {
        String loginPath = configuration.getParameter(LOGIN_PATH_PARAM);
        if (loginPath != null)
            setLoginPath(loginPath);

        for (Authenticator authenticator : _authenticatorsMappings.values())
        {
            authenticator.setConfiguration(configuration);
        }
    }

    public void setLoginPath(String loginPath)
    {
        if (loginPath != null)
        {
            if (!loginPath.startsWith("/"))
            {
                LOG.warn("login path must start with /");
                loginPath = "/" + loginPath;
            }

            _loginPath = loginPath;
        }
    }

    public boolean isLoginPage(String uri)
    {
        return matchURI(uri, _loginPath);
    }

    private boolean matchURI(String uri, String path)
    {
        int jsc = uri.indexOf(path);
        if (jsc < 0)
            return false;
        int e = jsc + path.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public void setDispatch(boolean dispatch)
    {
        _dispatch = dispatch;
    }

    @Override
    public String getAuthenticationType()
    {
        return "MULTI";
    }

    @Override
    public UserIdentity login(String username, Object password, Request request, Response response)
    {
        Authenticator authenticator = getAuthenticator(request.getSession(false));
        if (authenticator instanceof LoginAuthenticator loginAuthenticator)
        {
            doLogin(request);
            return loginAuthenticator.login(username, password, request, response);
        }

        return super.login(username, password, request, response);
    }

    @Override
    public void logout(Request request, Response response)
    {
        Authenticator authenticator = getAuthenticator(request.getSession(false));
        if (authenticator instanceof LoginAuthenticator loginAuthenticator)
        {
            loginAuthenticator.logout(request, response);
            doLogout(request);
        }

        super.logout(request, response);
    }

    @Override
    public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        Session session = getSession.apply(true);

        // If we are logged in we should always use that authenticator until logged out.
        if (isLoggedIn(session))
        {
            Authenticator authenticator = getAuthenticator(session);
            return authenticator.getConstraintAuthentication(pathInContext, existing, getSession);
        }

        Authenticator authenticator = null;
        MatchedResource<Authenticator> matched = _authenticatorsMappings.getMatched(pathInContext);
        if (matched != null)
            authenticator = matched.getResource();
        if (authenticator == null)
            authenticator = getAuthenticator(session);
        if (authenticator == null)
            authenticator = _defaultAuthenticator;
        saveAuthenticator(session, authenticator);
        return authenticator.getConstraintAuthentication(pathInContext, existing, getSession);
    }

    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
    {
        Session session = request.getSession(true);
        Authenticator authenticator = getAuthenticator(session);
        if (authenticator == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return AuthenticationState.SEND_FAILURE;
        }

        AuthenticationState authenticationState = authenticator.validateRequest(request, response, callback);
        if (authenticationState instanceof AuthenticationState.ResponseSent)
        {
            if (authenticationState instanceof LoginAuthenticator.UserAuthenticationSent)
                doLogin(request);
            return authenticationState;
        }

        if (authenticationState instanceof AuthenticationState.Succeeded succeededState)
            return new MultiSucceededAuthenticationState(succeededState);
        else if (authenticationState instanceof AuthenticationState.Deferred deferredState)
            return new MultiDelegateAuthenticationState(deferredState);
        return authenticationState;
    }

    @Override
    public Request prepareRequest(Request request, AuthenticationState authenticationState)
    {
        Session session = request.getSession(true);
        Authenticator authenticator = getAuthenticator(session);
        if (authenticator == null)
            throw new IllegalStateException("No authenticator found");
        return authenticator.prepareRequest(request, authenticationState);
    }

    private static class MultiDelegateAuthenticationState implements AuthenticationState.Deferred
    {
        private final AuthenticationState.Deferred _delegate;

        public MultiDelegateAuthenticationState(AuthenticationState.Deferred state)
        {
            _delegate = state;
        }

        @Override
        public Succeeded authenticate(Request request)
        {
            return _delegate.authenticate(request);
        }

        @Override
        public AuthenticationState authenticate(Request request, Response response, Callback callback)
        {
            return _delegate.authenticate(request, response, callback);
        }

        @Override
        public Succeeded login(String username, Object password, Request request, Response response)
        {
            Succeeded succeeded = _delegate.login(username, password, request, response);
            if (succeeded != null)
                doLogin(request);
            return succeeded;
        }

        @Override
        public void logout(Request request, Response response)
        {
            _delegate.logout(request, response);
            doLogout(request);
        }

        @Override
        public IdentityService.Association getAssociation()
        {
            return _delegate.getAssociation();
        }

        @Override
        public Principal getUserPrincipal()
        {
            return _delegate.getUserPrincipal();
        }
    }

    private static class MultiSucceededAuthenticationState implements AuthenticationState.Succeeded
    {
        private final AuthenticationState.Succeeded _delegate;

        public MultiSucceededAuthenticationState(AuthenticationState.Succeeded state)
        {
            _delegate = state;
        }

        @Override
        public String getAuthenticationType()
        {
            return _delegate.getAuthenticationType();
        }

        @Override
        public UserIdentity getUserIdentity()
        {
            return _delegate.getUserIdentity();
        }

        @Override
        public Principal getUserPrincipal()
        {
            return _delegate.getUserPrincipal();
        }

        @Override
        public boolean isUserInRole(String role)
        {
            return _delegate.isUserInRole(role);
        }

        @Override
        public void logout(Request request, Response response)
        {
            _delegate.logout(request, response);
            doLogout(request);
        }
    }

    private class DefaultAuthenticator implements Authenticator
    {
        @Override
        public void setConfiguration(Configuration configuration)
        {
        }

        @Override
        public String getAuthenticationType()
        {
            return "DEFAULT";
        }

        @Override
        public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
        {
            if (isLoginPage(pathInContext))
                return Constraint.Authorization.ALLOWED;
            return existing;
        }

        @Override
        public AuthenticationState validateRequest(Request request, Response response, Callback callback)
        {
            if (_loginPath != null)
            {
                String loginPath = URIUtil.addPaths(request.getContext().getContextPath(), _loginPath);
                if (_dispatch)
                {
                    HttpURI.Mutable newUri = HttpURI.build(request.getHttpURI()).pathQuery(loginPath);
                    return new AuthenticationState.ServeAs(newUri);
                }
                else
                {
                    Session session = request.getSession(true);
                    String redirectUri = session.encodeURI(request, loginPath, true);
                    Response.sendRedirect(request, response, callback, redirectUri, true);
                    return AuthenticationState.CHALLENGE;
                }
            }
            return null;
        }
    }

    private static MultiAuthState getAuthState(Session session)
    {
        if (session == null)
            return null;
        return (MultiAuthState)session.getAttribute(AUTH_STATE_ATTR);
    }

    private static MultiAuthState ensureAuthState(Session session)
    {
        if (session == null)
            throw new IllegalArgumentException();

        MultiAuthState authState = (MultiAuthState)session.getAttribute(AUTH_STATE_ATTR);
        if (authState == null)
        {
            authState = new MultiAuthState();
            session.setAttribute(AUTH_STATE_ATTR, authState);
        }
        return authState;
    }

    private static boolean isLoggedIn(Session session)
    {
        if (session == null)
            return false;

        synchronized (session)
        {
            MultiAuthState authState = getAuthState(session);
            return authState != null && authState.isLoggedIn();
        }
    }

    private static void doLogin(Request request)
    {
        Session session = request.getSession(true);
        if (session != null)
        {
            synchronized (session)
            {
                MultiAuthState authState = ensureAuthState(session);
                authState.setLogin(true);
            }
        }
    }

    private static void doLogout(Request request)
    {
        Session session = request.getSession(false);
        if (session != null)
        {
            synchronized (session)
            {
                session.removeAttribute(AUTH_STATE_ATTR);
            }
        }
    }

    private void saveAuthenticator(Session session, Authenticator authenticator)
    {
        if (session == null)
            throw new IllegalArgumentException();

        synchronized (session)
        {
            MultiAuthState authState = ensureAuthState(session);
            authState.setAuthenticatorName(authenticator.getClass().getName());
        }
    }

    private Authenticator getAuthenticator(Session session)
    {
        if (session == null)
            return null;

        synchronized (session)
        {
            MultiAuthState state = getAuthState(session);
            if (state == null || state.getAuthenticatorName() == null)
                return null;

            String name = state.getAuthenticatorName();
            if (_defaultAuthenticator.getClass().getName().equals(name))
                return _defaultAuthenticator;
            for (Authenticator authenticator : _authenticatorsMappings.values())
            {
                if (name.equals(authenticator.getClass().getName()))
                    return authenticator;
            }

            return null;
        }
    }

    private static class MultiAuthState implements Serializable
    {
        @Serial
        private static final long serialVersionUID = -4292431864385753482L;

        private String _authenticatorName;
        private boolean _isLoggedIn;

        public MultiAuthState()
        {
        }

        public void setAuthenticatorName(String authenticatorName)
        {
            _authenticatorName = authenticatorName;
        }

        public String getAuthenticatorName()
        {
            return _authenticatorName;
        }

        public void setLogin(boolean isLoggedIn)
        {
            _isLoggedIn = isLoggedIn;
        }

        private boolean isLoggedIn()
        {
            return _isLoggedIn;
        }
    }
}
