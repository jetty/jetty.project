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

package org.eclipse.jetty.ee9.jaas;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee9.jaas.callback.DefaultCallbackHandler;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.DefaultIdentityService;
import org.eclipse.jetty.ee9.security.IdentityService;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAASLoginService
 *
 *
 * Implementation of jetty's LoginService that works with JAAS for
 * authorization and authentication.
 */
public class JAASLoginService extends ContainerLifeCycle implements LoginService
{
    private static final Logger LOG = LoggerFactory.getLogger(JAASLoginService.class);

    public static final String DEFAULT_ROLE_CLASS_NAME = "org.eclipse.jetty.ee9.jaas.JAASRole";
    public static final String[] DEFAULT_ROLE_CLASS_NAMES = {DEFAULT_ROLE_CLASS_NAME};
    public static final ThreadLocal<JAASLoginService> INSTANCE = new ThreadLocal<>();
    
    protected String[] _roleClassNames = DEFAULT_ROLE_CLASS_NAMES;
    protected String _callbackHandlerClass;
    protected String _realmName;
    protected String _loginModuleName;
    protected JAASUserPrincipal _defaultUser = new JAASUserPrincipal(null, null, null);
    protected IdentityService _identityService;
    protected Configuration _configuration;

    public JAASLoginService()
    {
    }

    /**
     * @param name the name of the realm
     */
    public JAASLoginService(String name)
    {
        this();
        _realmName = name;
        _loginModuleName = name;
    }

    /**
     * Get the name of the realm.
     *
     * @return name or null if not set.
     */
    @Override
    public String getName()
    {
        return _realmName;
    }

    /**
     * Set the name of the realm
     *
     * @param name a <code>String</code> value
     */
    public void setName(String name)
    {
        _realmName = name;
    }

    /**
     * @return the configuration
     */
    public Configuration getConfiguration()
    {
        return _configuration;
    }

    /**
     * @param configuration the configuration to set
     */
    public void setConfiguration(Configuration configuration)
    {
        _configuration = configuration;
    }

    /**
     * Get the identityService.
     *
     * @return the identityService
     */
    @Override
    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    /**
     * Set the identityService.
     *
     * @param identityService the identityService to set
     */
    @Override
    public void setIdentityService(IdentityService identityService)
    {
        _identityService = identityService;
    }

    /**
     * Set the name to use to index into the config
     * file of LoginModules.
     *
     * @param name a <code>String</code> value
     */
    public void setLoginModuleName(String name)
    {
        _loginModuleName = name;
    }

    public void setCallbackHandlerClass(String classname)
    {
        _callbackHandlerClass = classname;
    }

    public void setRoleClassNames(String[] classnames)
    {
        if (classnames == null || classnames.length == 0)
        {
            _roleClassNames = DEFAULT_ROLE_CLASS_NAMES;
            return;
        }

        _roleClassNames = ArrayUtil.addToArray(classnames, DEFAULT_ROLE_CLASS_NAME, String.class);
    }

    public String[] getRoleClassNames()
    {
        return _roleClassNames;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_identityService == null)
            _identityService = new DefaultIdentityService();
        addBean(new PropertyUserStoreManager());
        super.doStart();
    }

    @Override
    public UserIdentity login(final String username, final Object credentials, final ServletRequest request)
    {
        try
        {
            CallbackHandler callbackHandler = null;
            if (_callbackHandlerClass == null)
                callbackHandler = new DefaultCallbackHandler();
            else
            {
                Class<?> clazz = Loader.loadClass(_callbackHandlerClass);
                callbackHandler = (CallbackHandler)clazz.getDeclaredConstructor().newInstance();
            }
            
            if (callbackHandler instanceof DefaultCallbackHandler)
            {
                DefaultCallbackHandler dch = (DefaultCallbackHandler)callbackHandler;
                if (request instanceof HttpServletRequest httpServletRequest)
                    dch.setRequest(httpServletRequest);
                dch.setCredential(credentials);
                dch.setUserName(username);
            }

            //set up the login context
            Subject subject = new Subject();
            INSTANCE.set(this);
            LoginContext loginContext = 
                (_configuration == null ? new LoginContext(_loginModuleName, subject, callbackHandler)
                : new LoginContext(_loginModuleName, subject, callbackHandler, _configuration));

            loginContext.login();

            //login success
            JAASUserPrincipal userPrincipal = new JAASUserPrincipal(getUserName(callbackHandler), subject, loginContext);
            subject.getPrincipals().add(userPrincipal);

            return _identityService.newUserIdentity(subject, userPrincipal, getGroups(subject));
        }
        catch (FailedLoginException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Login failed", e);
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Login error", e);
        }
        finally
        {
            INSTANCE.remove();
        }
        
        return null;
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        // TODO optionally check user is still valid
        return true;
    }

    private String getUserName(CallbackHandler callbackHandler) throws IOException, UnsupportedCallbackException
    {
        NameCallback nameCallback = new NameCallback("foo");
        callbackHandler.handle(new Callback[]{nameCallback});
        return nameCallback.getName();
    }

    @Override
    public void logout(UserIdentity user)
    {
        Set<JAASUserPrincipal> userPrincipals = user.getSubject().getPrincipals(JAASUserPrincipal.class);
        LoginContext loginContext = userPrincipals.iterator().next().getLoginContext();
        try
        {
            loginContext.logout();
        }
        catch (LoginException e)
        {
            LOG.warn("Failed to logout {}", user, e);
        }
    }

    /**
     * Get all of the groups for the user.
     *
     * @param subject the Subject representing the user
     * @return all the names of groups that the user is in, or 0 length array if none
     */
    protected String[] getGroups(Subject subject)
    {
        Collection<String> groups = new LinkedHashSet<>();
        for (Principal principal : subject.getPrincipals())
        {
            if (isRoleClass(principal.getClass(), Arrays.asList(getRoleClassNames())))
                groups.add(principal.getName());
        }

        return groups.toArray(new String[groups.size()]);
    }
    
    /**
     * Check whether the class, its superclasses or any interfaces they implement
     * is one of the classes that represents a role.
     * 
     * @param clazz the class to check
     * @param roleClassNames the list of classnames that represent roles
     * @return true if the class is a role class
     */
    private static boolean isRoleClass(Class<?> clazz, List<String> roleClassNames)
    {
        Class<?> c = clazz;
        
        //add the class, its interfaces and superclasses to the list to test
        List<String> classnames = new ArrayList<>();
        while (c != null)
        {
            classnames.add(c.getName());
            Arrays.stream(c.getInterfaces()).map(Class::getName).forEach(classnames::add);
            c = c.getSuperclass();
        }
        
        return roleClassNames.stream().anyMatch(classnames::contains);
    }
}
