//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.jaas;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.jaas.callback.DefaultCallbackHandler;
import org.eclipse.jetty.jaas.callback.ObjectCallback;
import org.eclipse.jetty.jaas.callback.RequestParameterCallback;
import org.eclipse.jetty.jaas.callback.ServletRequestCallback;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * JAASLoginService
 *
 *
 * Implementation of jetty's LoginService that works with JAAS for
 * authorization and authentication.
 */
public class JAASLoginService extends AbstractLifeCycle implements LoginService
{
    private static final Logger LOG = Log.getLogger(JAASLoginService.class);

    public static final String DEFAULT_ROLE_CLASS_NAME = "org.eclipse.jetty.jaas.JAASRole";
    public static final String[] DEFAULT_ROLE_CLASS_NAMES = {DEFAULT_ROLE_CLASS_NAME};

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
        super.doStart();
    }

    @Override
    public UserIdentity login(final String username, final Object credentials, final ServletRequest request)
    {
        try
        {
            CallbackHandler callbackHandler = null;
            if (_callbackHandlerClass == null)
            {
                callbackHandler = new CallbackHandler()
                {
                    @Override
                    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
                    {
                        for (Callback callback : callbacks)
                        {
                            if (callback instanceof NameCallback)
                            {
                                ((NameCallback)callback).setName(username);
                            }
                            else if (callback instanceof PasswordCallback)
                            {
                                ((PasswordCallback)callback).setPassword(credentials.toString().toCharArray());
                            }
                            else if (callback instanceof ObjectCallback)
                            {
                                ((ObjectCallback)callback).setObject(credentials);
                            }
                            else if (callback instanceof RequestParameterCallback)
                            {
                                RequestParameterCallback rpc = (RequestParameterCallback)callback;
                                if (request != null)
                                    rpc.setParameterValues(Arrays.asList(request.getParameterValues(rpc.getParameterName())));
                            }
                            else if (callback instanceof ServletRequestCallback)
                            {
                                ((ServletRequestCallback)callback).setRequest(request);
                            }
                            else
                                throw new UnsupportedCallbackException(callback);
                        }
                    }
                };
            }
            else
            {
                Class<?> clazz = Loader.loadClass(_callbackHandlerClass);
                callbackHandler = (CallbackHandler)clazz.getDeclaredConstructor().newInstance();
                if (DefaultCallbackHandler.class.isAssignableFrom(clazz))
                {
                    DefaultCallbackHandler dch = (DefaultCallbackHandler)callbackHandler;
                    if (request instanceof Request)
                        dch.setRequest((Request)request);
                    dch.setCredential(credentials);
                    dch.setUserName(username);
                }
            }

            //set up the login context
            Subject subject = new Subject();
            LoginContext loginContext = (_configuration == null ? new LoginContext(_loginModuleName, subject, callbackHandler)
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
            LOG.ignore(e);
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
            LOG.warn(e);
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
        Set<Principal> principals = subject.getPrincipals();
        for (Principal principal : principals)
        {
            Class<?> c = principal.getClass();
            while (c != null)
            {
                if (roleClassNameMatches(c.getName()))
                {
                    groups.add(principal.getName());
                    break;
                }

                boolean added = false;
                for (Class<?> ci : c.getInterfaces())
                {
                    if (roleClassNameMatches(ci.getName()))
                    {
                        groups.add(principal.getName());
                        added = true;
                        break;
                    }
                }

                if (!added)
                {
                    c = c.getSuperclass();
                }
                else
                    break;
            }
        }

        return groups.toArray(new String[groups.size()]);
    }

    private boolean roleClassNameMatches(String classname)
    {
        boolean result = false;
        for (String roleClassName : getRoleClassNames())
        {
            if (roleClassName.equals(classname))
            {
                result = true;
                break;
            }
        }
        return result;
    }
}
