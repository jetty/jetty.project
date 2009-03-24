// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jaas.spi;

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
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import org.eclipse.jetty.plus.jaas.JAASPrincipal;
import org.eclipse.jetty.plus.jaas.JAASRole;
import org.eclipse.jetty.plus.jaas.callback.ObjectCallback;

/**
 * AbstractLoginModule
 *
 * Abstract base class for all LoginModules. Subclasses should 
 * just need to implement getUserInfo method.
 *
 */
public abstract class AbstractLoginModule implements LoginModule
{
    private CallbackHandler callbackHandler;
    
    private boolean authState = false;
    private boolean commitState = false;
    private JAASUserInfo currentUser;
    private Subject subject;
    
    public class JAASUserInfo
    {
        private UserInfo user;
        private Principal principal;
        private List roles;
              
        public JAASUserInfo (UserInfo u)
        {
            setUserInfo(u);
        }
        
        public String getUserName ()
        {
            return this.user.getUserName();
        }
        
        public Principal getPrincipal()
        {
            return this.principal;
        }
        
        public void setUserInfo (UserInfo u)
        {
            this.user = u;
            this.principal = new JAASPrincipal(u.getUserName());
            this.roles = new ArrayList();
            if (u.getRoleNames() != null)
            {
                Iterator itor = u.getRoleNames().iterator();
                while (itor.hasNext())
                    this.roles.add(new JAASRole((String)itor.next()));
            }
        }
               
        public void setJAASInfo (Subject subject)
        {
            subject.getPrincipals().add(this.principal);
            subject.getPrivateCredentials().add(this.user.getCredential());
            subject.getPrincipals().addAll(roles);
        }
        
        public void unsetJAASInfo (Subject subject)
        {
            subject.getPrincipals().remove(this.principal);
            subject.getPrivateCredentials().remove(this.user.getCredential());
            subject.getPrincipals().removeAll(this.roles);
        }
        
        public boolean checkCredential (Object suppliedCredential)
        {
            return this.user.checkCredential(suppliedCredential);
        }
    }
    
    
    
    public Subject getSubject ()
    {
        return this.subject;
    }
    
    public void setSubject (Subject s)
    {
        this.subject = s;
    }
    
    public JAASUserInfo getCurrentUser()
    {
        return this.currentUser;
    }
    
    public void setCurrentUser (JAASUserInfo u)
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
    
    public boolean isCommitted ()
    {
        return this.commitState;
    }
    
    public void setAuthenticated (boolean authState)
    {
        this.authState = authState;
    }
    
    public void setCommitted (boolean commitState)
    {
        this.commitState = commitState;
    }
    /** 
     * @see javax.security.auth.spi.LoginModule#abort()
     * @throws LoginException
     */
    public boolean abort() throws LoginException
    {
        this.currentUser = null;
        return (isAuthenticated() && isCommitted());
    }

    /** 
     * @see javax.security.auth.spi.LoginModule#commit()
     * @return
     * @throws LoginException
     */
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

    
    public Callback[] configureCallbacks ()
    {
     
        Callback[] callbacks = new Callback[2];
        callbacks[0] = new NameCallback("Enter user name");
        callbacks[1] = new ObjectCallback();
        return callbacks;
    }
    
    
    
    public abstract UserInfo getUserInfo (String username) throws Exception;
    
    
    
    /** 
     * @see javax.security.auth.spi.LoginModule#login()
     * @return
     * @throws LoginException
     */
    public boolean login() throws LoginException
    {
        try
        {  
            if (callbackHandler == null)
                throw new LoginException ("No callback handler");
            
            Callback[] callbacks = configureCallbacks();
            callbackHandler.handle(callbacks);

            String webUserName = ((NameCallback)callbacks[0]).getName();
            Object webCredential = ((ObjectCallback)callbacks[1]).getObject();

            if ((webUserName == null) || (webCredential == null))
            {
                setAuthenticated(false);
                return isAuthenticated();
            }
            
            UserInfo userInfo = getUserInfo(webUserName);
            
            if (userInfo == null)
            {
                setAuthenticated(false);
                return isAuthenticated();
            }
            
            currentUser = new JAASUserInfo(userInfo);
            setAuthenticated(currentUser.checkCredential(webCredential));
            return isAuthenticated();
        }
        catch (IOException e)
        {
            throw new LoginException (e.toString());
        }
        catch (UnsupportedCallbackException e)
        {
            throw new LoginException (e.toString());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            throw new LoginException (e.toString());
        }
    }

    /** 
     * @see javax.security.auth.spi.LoginModule#logout()
     * @return
     * @throws LoginException
     */
    public boolean logout() throws LoginException
    {
        this.currentUser.unsetJAASInfo(this.subject);
        return true;
    }

    /** 
     * @see javax.security.auth.spi.LoginModule#initialize(javax.security.auth.Subject, javax.security.auth.callback.CallbackHandler, java.util.Map, java.util.Map)
     * @param subject
     * @param callbackHandler
     * @param sharedState
     * @param options
     */
    public void initialize(Subject subject, CallbackHandler callbackHandler,
            Map sharedState, Map options)
    {
        this.callbackHandler = callbackHandler;
        this.subject = subject;
    }

}
