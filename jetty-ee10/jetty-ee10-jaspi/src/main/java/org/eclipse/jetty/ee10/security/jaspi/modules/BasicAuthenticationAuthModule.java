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

package org.eclipse.jetty.ee10.security.jaspi.modules;

import java.io.IOException;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.security.jaspi.JaspiMessageInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.security.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * A {@link ServerAuthModule} implementation of HTTP Basic Authentication.  
 */
public class BasicAuthenticationAuthModule extends BaseAuthModule
{
    private static final Logger LOG = LoggerFactory.getLogger(BasicAuthenticationAuthModule.class);

    private String _realmName;

    private static final String REALM_KEY = "org.eclipse.jetty.ee10.security.jaspi.modules.RealmName";

    public BasicAuthenticationAuthModule()
    {
    }

    public BasicAuthenticationAuthModule(CallbackHandler callbackHandler, String realmName)
    {
        super(callbackHandler);
        _realmName = realmName;
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler callbackHandler, Map options) throws AuthException
    {
        super.initialize(requestPolicy, responsePolicy, callbackHandler, options);
        _realmName = (String)options.get(REALM_KEY);
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
    {
        if (!(messageInfo instanceof JaspiMessageInfo))
            throw new IllegalStateException("Not a JaspiMessageInfo");
        JaspiMessageInfo jaspiMessageInfo = (JaspiMessageInfo)messageInfo;
        
        Request request = jaspiMessageInfo.getBaseRequest();
        Response response = jaspiMessageInfo.getBaseResponse();
        
        String credentials = request.getHeaders().get(HttpHeader.AUTHORIZATION.asString());

        try
        {
            if (credentials != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Credentials: {}", credentials);
                if (login(clientSubject, credentials, Constraint.__BASIC_AUTH, messageInfo))
                {
                    return AuthStatus.SUCCESS;
                }
            }

            if (!isMandatory(messageInfo))
            {
                return AuthStatus.SUCCESS;
            }
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE.asString(), "basic realm=\"" + _realmName + '"');
            Response.writeError(request, response, jaspiMessageInfo.getCallback(), HttpServletResponse.SC_UNAUTHORIZED);
            return AuthStatus.SEND_CONTINUE;
        }
        catch (IOException | UnsupportedCallbackException e)
        {
            throw new AuthException(e.getMessage());
        }
    }
}
