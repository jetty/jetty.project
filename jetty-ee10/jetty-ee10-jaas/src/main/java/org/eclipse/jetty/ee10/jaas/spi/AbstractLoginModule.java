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

package org.eclipse.jetty.ee10.jaas.spi;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import org.eclipse.jetty.ee10.jaas.JAASRole;
import org.eclipse.jetty.ee10.jaas.callback.ObjectCallback;
import org.eclipse.jetty.ee10.servlet.security.UserPrincipal;

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
    private JAASUser currentUser;
    private Subject subject;

    public abstract static class JAASUser
    {
        private final UserPrincipal _user;
        private List<JAASRole> _roles;
        
        public JAASUser(UserPrincipal u)
        {
            _user = u;
        }

        public String getUserName()
        {
            return _user.getName();
        }

        /**
         * @param subject The subject
         */
        public void setJAASInfo(Subject subject)
        {
            if (_user == null)
                return;

            _user.configureSubject(subject);
            if (_roles != null)
                subject.getPrincipals().addAll(_roles);
        }

        /**
         * @param subject The subject
         */
        public void unsetJAASInfo(Subject subject)
        {
            if (_user == null)
                return;
            _user.deconfigureSubject(subject);
            if (_roles != null)
                subject.getPrincipals().removeAll(_roles);
        }

        public boolean checkCredential(Object suppliedCredential)
        {
            return _user.authenticate(suppliedCredential);
        }

        public void fetchRoles() throws Exception
        {
            List<String> rolenames = doFetchRoles();
            if (rolenames != null)
                _roles = rolenames.stream().map(JAASRole::new).collect(Collectors.toList());
        }
        
        public abstract List<String> doFetchRoles() throws Exception;
    }

    public abstract JAASUser getUser(String username) throws Exception;

    public Subject getSubject()
    {
        return this.subject;
    }

    public void setSubject(Subject s)
    {
        this.subject = s;
    }

    public JAASUser getCurrentUser()
    {
        return this.currentUser;
    }

    public void setCurrentUser(JAASUser u)
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

            JAASUser user = getUser(webUserName);

            if (user == null)
            {
                setAuthenticated(false);
                throw new FailedLoginException();
            }

            currentUser = user;
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
