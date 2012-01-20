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

package org.eclipse.jetty.security.jaspi.modules;

import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.security.CrossContextPsuedoSession;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginCallbackImpl;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * @deprecated use *ServerAuthentication
 * @version $Rev: 4792 $ $Date: 2009-03-18 22:55:52 +0100 (Wed, 18 Mar 2009) $
 */
public class FormAuthModule extends BaseAuthModule
{
    private static final Logger LOG = Log.getLogger(FormAuthModule.class);

    /* ------------------------------------------------------------ */
    public final static String __J_URI = "org.eclipse.jetty.util.URI";

    public final static String __J_AUTHENTICATED = "org.eclipse.jetty.server.Auth";

    public final static String __J_SECURITY_CHECK = "/j_security_check";

    public final static String __J_USERNAME = "j_username";

    public final static String __J_PASSWORD = "j_password";

    // private String realmName;
    public static final String LOGIN_PAGE_KEY = "org.eclipse.jetty.security.jaspi.modules.LoginPage";

    public static final String ERROR_PAGE_KEY = "org.eclipse.jetty.security.jaspi.modules.ErrorPage";

    public static final String SSO_SOURCE_KEY = "org.eclipse.jetty.security.jaspi.modules.SsoSource";

    private String _formErrorPage;

    private String _formErrorPath;

    private String _formLoginPage;

    private String _formLoginPath;

    private CrossContextPsuedoSession<UserInfo> ssoSource;

    public FormAuthModule()
    {
    }

    public FormAuthModule(CallbackHandler callbackHandler, String loginPage, String errorPage)
    {
        super(callbackHandler);
        setLoginPage(loginPage);
        setErrorPage(errorPage);
    }

    public FormAuthModule(CallbackHandler callbackHandler, CrossContextPsuedoSession<UserInfo> ssoSource, 
                          String loginPage, String errorPage)
    {
        super(callbackHandler);
        this.ssoSource = ssoSource;
        setLoginPage(loginPage);
        setErrorPage(errorPage);
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, 
                           CallbackHandler handler, Map options) 
    throws AuthException
    {
        super.initialize(requestPolicy, responsePolicy, handler, options);
        setLoginPage((String) options.get(LOGIN_PAGE_KEY));
        setErrorPage((String) options.get(ERROR_PAGE_KEY));
        ssoSource = (CrossContextPsuedoSession<UserInfo>) options.get(SSO_SOURCE_KEY);
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
        if (_formLoginPath.indexOf('?') > 0) _formLoginPath = _formLoginPath.substring(0, _formLoginPath.indexOf('?'));
    }

    /* ------------------------------------------------------------ */
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

            if (_formErrorPath.indexOf('?') > 0) _formErrorPath = _formErrorPath.substring(0, _formErrorPath.indexOf('?'));
        }
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
    {
       
        HttpServletRequest request = (HttpServletRequest) messageInfo.getRequestMessage();
        HttpServletResponse response = (HttpServletResponse) messageInfo.getResponseMessage();
        String uri = request.getRequestURI();
        if (uri==null)
            uri=URIUtil.SLASH;
        
        boolean mandatory = isMandatory(messageInfo);  
        mandatory |= isJSecurityCheck(uri);
        HttpSession session = request.getSession(mandatory);
        
        // not mandatory or its the login or login error page don't authenticate
        if (!mandatory || isLoginOrErrorPage(URIUtil.addPaths(request.getServletPath(),request.getPathInfo()))) return AuthStatus.SUCCESS;

        try
        {
            // Handle a request for authentication.
            if (isJSecurityCheck(uri))
            {
                final String username = request.getParameter(__J_USERNAME);
                final String password = request.getParameter(__J_PASSWORD);
            
                boolean success = tryLogin(messageInfo, clientSubject, response, session, username, new Password(password));
                if (success)
                {
                    // Redirect to original request                    
                    String nuri=null;
                    synchronized(session)
                    {
                        nuri = (String) session.getAttribute(__J_URI);
                    }
                    
                    if (nuri == null || nuri.length() == 0)
                    {
                        nuri = request.getContextPath();
                        if (nuri.length() == 0) 
                            nuri = URIUtil.SLASH;
                    }
                   
                    response.setContentLength(0);   
                    response.sendRedirect(response.encodeRedirectURL(nuri));
                    return AuthStatus.SEND_CONTINUE;
                }
                // not authenticated
                if (LOG.isDebugEnabled()) LOG.debug("Form authentication FAILED for " + StringUtil.printable(username));
                if (_formErrorPage == null)
                {
                    if (response != null) response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                else
                {
                    response.setContentLength(0);
                    response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(request.getContextPath(), _formErrorPage)));
                }
                // TODO is this correct response if isMandatory false??? Can
                // that occur?
                return AuthStatus.SEND_FAILURE;
            }
            
            
            // Check if the session is already authenticated.
            FormCredential form_cred = (FormCredential) session.getAttribute(__J_AUTHENTICATED);
            if (form_cred != null)
            {                
                //TODO: ideally we would like the form auth module to be able to invoke the 
                //loginservice.validate() method to check the previously authed user, but it is not visible
                //to FormAuthModule
                if (form_cred._subject == null)
                    return AuthStatus.SEND_FAILURE;
                Set<Object> credentials = form_cred._subject.getPrivateCredentials();
                if (credentials == null || credentials.isEmpty())
                    return AuthStatus.SEND_FAILURE; //if no private credentials, assume it cannot be authenticated

                clientSubject.getPrivateCredentials().addAll(credentials);

                //boolean success = tryLogin(messageInfo, clientSubject, response, session, form_cred._jUserName, new Password(new String(form_cred._jPassword)));
                return AuthStatus.SUCCESS;  
            }
            else if (ssoSource != null)
            {
                UserInfo userInfo = ssoSource.fetch(request);
                if (userInfo != null)
                {
                    boolean success = tryLogin(messageInfo, clientSubject, response, session, userInfo.getUserName(), new Password(new String(userInfo.getPassword())));
                    if (success) { return AuthStatus.SUCCESS; }
                }
            }
            
           

            // if we can't send challenge
            if (DeferredAuthentication.isDeferred(response))
                return AuthStatus.SUCCESS; 
            

            // redirect to login page  
            StringBuffer buf = request.getRequestURL();
            if (request.getQueryString() != null)
                buf.append("?").append(request.getQueryString());

            synchronized (session)
            {
                session.setAttribute(__J_URI, buf.toString());
            }
            
            response.setContentLength(0);
            response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(request.getContextPath(), _formLoginPage)));
            return AuthStatus.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new AuthException(e.getMessage());
        }
        catch (UnsupportedCallbackException e)
        {
            throw new AuthException(e.getMessage());
        }

    }
    
    /* ------------------------------------------------------------ */
    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(__J_SECURITY_CHECK);
        
        if (jsc<0)
            return false;
        int e=jsc+__J_SECURITY_CHECK.length();
        if (e==uri.length())
            return true;
        char c = uri.charAt(e);
        return c==';'||c=='#'||c=='/'||c=='?';
    }

    private boolean tryLogin(MessageInfo messageInfo, Subject clientSubject, 
                             HttpServletResponse response, HttpSession session, 
                             String username, Password password) 
    throws AuthException, IOException, UnsupportedCallbackException
    {
        if (login(clientSubject, username, password, Constraint.__FORM_AUTH, messageInfo))
        {
            char[] pwdChars = password.toString().toCharArray();
            Set<LoginCallbackImpl> loginCallbacks = clientSubject.getPrivateCredentials(LoginCallbackImpl.class);
           
            if (!loginCallbacks.isEmpty())
            {
                LoginCallbackImpl loginCallback = loginCallbacks.iterator().next();
                FormCredential form_cred = new FormCredential(username, pwdChars, loginCallback.getUserPrincipal(), loginCallback.getSubject());
                session.setAttribute(__J_AUTHENTICATED, form_cred);
            }

            // Sign-on to SSO mechanism
            if (ssoSource != null)
            {
                UserInfo userInfo = new UserInfo(username, pwdChars);
                ssoSource.store(userInfo, response);
            }
            return true;
        }
        return false;
    }

    public boolean isLoginOrErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_formErrorPath) || pathInContext.equals(_formLoginPath));
    }

    /* ------------------------------------------------------------ */
    /**
     * FORM Authentication credential holder.
     */
    private static class FormCredential implements Serializable, HttpSessionBindingListener
    {
        String _jUserName;

        char[] _jPassword;

        transient Principal _userPrincipal;
        
        transient Subject _subject;

        private FormCredential(String _jUserName, char[] _jPassword, Principal _userPrincipal, Subject subject)
        {
            this._jUserName = _jUserName;
            this._jPassword = _jPassword;
            this._userPrincipal = _userPrincipal;
            this._subject = subject;
        }

        public void valueBound(HttpSessionBindingEvent event)
        {
        }

        public void valueUnbound(HttpSessionBindingEvent event)
        {
            if (LOG.isDebugEnabled()) LOG.debug("Logout " + _jUserName);

            // TODO jaspi call cleanSubject()
            // if (_realm instanceof SSORealm)
            // ((SSORealm) _realm).clearSingleSignOn(_jUserName);
            //
            // if (_realm != null && _userPrincipal != null)
            // _realm.logout(_userPrincipal);
        }

        public int hashCode()
        {
            return _jUserName.hashCode() + _jPassword.hashCode();
        }

        public boolean equals(Object o)
        {
            if (!(o instanceof FormCredential)) return false;
            FormCredential fc = (FormCredential) o;
            return _jUserName.equals(fc._jUserName) && Arrays.equals(_jPassword, fc._jPassword);
        }

        public String toString()
        {
            return "Cred[" + _jUserName + "]";
        }

    }

}
