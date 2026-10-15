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

package org.eclipse.jetty.ee9.security.jaspi;

import java.util.HashMap;
import java.util.Map;

import jakarta.security.auth.message.MessageInfo;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@link MessageInfo} of a request, carrying the message keys the Servlet Container Profile
 * defines between the container and the module.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JaspiMessageInfo implements MessageInfo
{
    public static final String MANDATORY_KEY = "jakarta.security.auth.message.MessagePolicy.isMandatory";
    public static final String AUTH_REQUEST_KEY = "jakarta.servlet.http.isAuthenticationRequest";
    public static final String AUTHENTICATION_TYPE_KEY = "jakarta.servlet.http.authType";
    public static final String REGISTER_SESSION_KEY = "jakarta.servlet.http.registerSession";

    /**
     * @deprecated use {@link #AUTHENTICATION_TYPE_KEY}, the name the profile gives this key.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public static final String AUTH_METHOD_KEY = AUTHENTICATION_TYPE_KEY;

    private final Map _map = new HashMap();
    private HttpServletRequest _request;
    private HttpServletResponse _response;

    public JaspiMessageInfo(ServletRequest request, ServletResponse response, boolean isAuthMandatory)
    {
        setRequestMessage(request);
        setResponseMessage(response);
        setMandatory(isAuthMandatory);
    }

    @Override
    public Map getMap()
    {
        return _map;
    }

    public String getAuthenticationType()
    {
        return (String)_map.get(AUTHENTICATION_TYPE_KEY);
    }

    /**
     * @deprecated use {@link #getAuthenticationType()}.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public String getAuthMethod()
    {
        return getAuthenticationType();
    }

    /**
     * @return whether authentication is mandatory for this message.
     * @deprecated read {@link #MANDATORY_KEY} from {@link #getMap()}.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public boolean isAuthMandatory()
    {
        return _map.get(MANDATORY_KEY) instanceof String value && Boolean.parseBoolean(value);
    }

    /**
     * @return whether the module asked for the authentication results of this message to be
     * remembered in a container authentication session.
     */
    public boolean isRegisterSession()
    {
        return _map.get(REGISTER_SESSION_KEY) instanceof String value && Boolean.parseBoolean(value);
    }

    public void setMandatory(boolean isMandatory)
    {
        if (isMandatory)
            _map.put(MANDATORY_KEY, "true");
        else
            _map.remove(MANDATORY_KEY);
    }

    /**
     * @param isAuthenticationRequest whether this message is the validation of a call the
     * application made to {@code HttpServletRequest.authenticate}.
     */
    public void setAuthenticationRequest(boolean isAuthenticationRequest)
    {
        if (isAuthenticationRequest)
            _map.put(AUTH_REQUEST_KEY, "true");
        else
            _map.remove(AUTH_REQUEST_KEY);
    }

    @Override
    public Object getRequestMessage()
    {
        return _request;
    }

    @Override
    public Object getResponseMessage()
    {
        return _response;
    }

    @Override
    public void setRequestMessage(Object request)
    {
        if (request instanceof HttpServletRequest httpServletRequest)
            _request = httpServletRequest;
        else
            throw new IllegalStateException("Not an HttpServletRequest");
    }

    @Override
    public void setResponseMessage(Object response)
    {
        if (response instanceof HttpServletResponse httpServletResponse)
            _response = httpServletResponse;
        else
            throw new IllegalStateException("Not an HttpServletResponse");
    }
}
