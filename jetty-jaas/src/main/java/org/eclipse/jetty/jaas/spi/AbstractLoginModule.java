//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.jaas.spi;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import org.eclipse.jetty.jaas.JAASPrincipal;
import org.eclipse.jetty.jaas.JAASRole;
import org.eclipse.jetty.jaas.callback.ObjectCallback;

/**
 * AbstractLoginModule
 *
 * Abstract base class for all LoginModules. Subclasses should
 * just need to implement getUserInfo method.
 */
public abstract class AbstractLoginModule implements LoginModule
{
    private CallbackHandler callbackHandler;

    private boolean authState = false;
    private boolean commitState = false;
    private JAASUserInfo currentUser;
    private Subject subject;

    /**
     * JAASUserInfo
     *
     * This class unites the UserInfo data with jaas concepts
     * such as Subject and Principals
     */
    public class JAASUserInfo
    {
        private UserInfo user;
        private Principal principal;
        private List<JAASRole> roles;

        public JAASUserInfo(UserInfo u)
        {
            this.user = u;
            this.principal = new JAASPrincipal(u.getUserName());
        }

        public String getUserName()
        {
            return this.user.getUserName();
        }

        public Principal getPrincipal()
        {
            return this.principal;
        }

        public void setJAASInfo(Subject subject)
        {
            subject.getPrincipals().add(this.principal);
            if (this.user.getCredential() != null)
            {
                subject.getPrivateCredentials().add(this.user.getCredential());
            }
            subject.getPrincipals().addAll(roles);
        }

        public void unsetJAASInfo(Subject subject)
        {
            subject.getPrincipals().remove(this.principal);
            if (this.user.getCredential() != null)
            {
                subject.getPrivateCredentials().remove(this.user.getCredential());
            }
            subject.getPrincipals().removeAll(this.roles);
        }

        public boolean checkCredential(Object suppliedCredential)
        {
            return this.user.checkCredential(suppliedCredential);
        }

        public void fetchRoles() throws Exception
        {
            this.user.fetchRoles();
            this.roles = new ArrayList<JAASRole>();
            if (this.user.getRoleNames() != null)
            {
                Iterator<String> itor = this.user.getRoleNames().iterator();
                while (itor.hasNext())
                {
                    this.roles.add(new JAASRole(itor.next()));
                }
            }
        }
    }

    public abstract UserInfo getUserInfo(String username) throws Exception;

    public Subject getSubject()
    {
        return this.subject;
    }

    public void setSubject(Subject s)
    {
        this.subject = s;
    }

    public JAASUserInfo getCurrentUser()
    {
        return this.currentUser;
    }

    public void setCurrentUser(JAASUserInfo u)
    {
        this.currentUser = u;
    }

    public CallbackHandler getCallbackHandler()
    {
        return this.callbackHandler;
    }

    public void setCallbackHandler(CallbackHandler h)
    {
        this.callbackHandler = h;
    }

    public boolean isAuthenticated()
    {
        return this.authState;
    }

    public boolean isCommitted()
    {
        return this.commitState;
    }

    public void setAuthenticated(boolean authState)
    {
        this.authState = authState;
    }

    public void setCommitted(boolean commitState)
    {
        this.commitState = commitState;
    }

    /**
     * @throws LoginException if unable to abort
     * @see javax.security.auth.spi.LoginModule#abort()
     */
    @Override
    public boolean abort() throws LoginException
    {
        this.currentUser = null;
        return (isAuthenticated() && isCommitted());
    }

    /**
     * @return true if committed, false if not (likely not authenticated)
     * @throws LoginException if unable to commit
     * @see javax.security.auth.spi.LoginModule#commit()
     */
    @Override
    public boolean commit() throws LoginException
    {
        if (!isAuthenticated())
        {
            currentUser = null;
            setCommitted(false);
            return false;
        }

        setCommitted(true);
        currentUser.setJAASInfo(subject);
        return true;
    }

    public Callback[] configureCallbacks()
    {
        Callback[] callbacks = new Callback[3];
        callbacks[0] = new NameCallback("Enter user name");
        callbacks[1] = new ObjectCallback();
        callbacks[2] = new PasswordCallback("Enter password", false); //only used if framework does not support the ObjectCallback
        return callbacks;
    }

    public boolean isIgnored()
    {
        return false;
    }

    /**
     * @return true if is authenticated, false otherwise
     * @throws LoginException if unable to login
     * @see javax.security.auth.spi.LoginModule#login()
     */
    @Override
    public boolean login() throws LoginException
    {
        try
        {
            if (isIgnored())
                return false;

            if (callbackHandler == null)
                throw new LoginException("No callback handler");

            Callback[] callbacks = configureCallbacks();
            callbackHandler.handle(callbacks);

            String webUserName = ((NameCallback)callbacks[0]).getName();
            Object webCredential = null;

            webCredential = ((ObjectCallback)callbacks[1]).getObject(); //first check if ObjectCallback has the credential
            if (webCredential == null)
                webCredential = ((PasswordCallback)callbacks[2]).getPassword(); //use standard PasswordCallback

            if ((webUserName == null) || (webCredential == null))
            {
                setAuthenticated(false);
                throw new FailedLoginException();
            }

            UserInfo userInfo = getUserInfo(webUserName);

            if (userInfo == null)
            {
                setAuthenticated(false);
                throw new FailedLoginException();
            }

            currentUser = new JAASUserInfo(userInfo);
            setAuthenticated(currentUser.checkCredential(webCredential));

            if (isAuthenticated())
            {
                currentUser.fetchRoles();
                return true;
            }
            else
                throw new FailedLoginException();
        }
        catch (IOException e)
        {
            throw new LoginException(e.toString());
        }
        catch (UnsupportedCallbackException e)
        {
            throw new LoginException(e.toString());
        }
        catch (Exception e)
        {
            if (e instanceof LoginException)
                throw (LoginException)e;
            throw new LoginException(e.toString());
        }
    }

    /**
     * @return true always
     * @throws LoginException if unable to logout
     * @see javax.security.auth.spi.LoginModule#logout()
     */
    @Override
    public boolean logout() throws LoginException
    {
        this.currentUser.unsetJAASInfo(this.subject);
        this.currentUser = null;
        return true;
    }

    /**
     * @param subject the subject
     * @param callbackHandler the callback handler
     * @param sharedState the shared state map
     * @param options the option map
     * @see javax.security.auth.spi.LoginModule#initialize(javax.security.auth.Subject, javax.security.auth.callback.CallbackHandler, java.util.Map, java.util.Map)
     */
    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler,
                           Map<String, ?> sharedState, Map<String, ?> options)
    {
        this.callbackHandler = callbackHandler;
        this.subject = subject;
    }
}
