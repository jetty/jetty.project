//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi.modules;

import java.io.IOException;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;

@Deprecated
public class BasicAuthModule extends BaseAuthModule
{
    private static final Logger LOG = Log.getLogger(BasicAuthModule.class);


    private String realmName;

    private static final String REALM_KEY = "org.eclipse.jetty.security.jaspi.modules.RealmName";

    public BasicAuthModule()
    {
    }

    public BasicAuthModule(CallbackHandler callbackHandler, String realmName)
    {
        super(callbackHandler);
        this.realmName = realmName;
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, 
                           CallbackHandler handler, Map options) 
    throws AuthException
    {
        super.initialize(requestPolicy, responsePolicy, handler, options);
        realmName = (String) options.get(REALM_KEY);
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, 
                                      Subject serviceSubject) 
    throws AuthException
    {
        HttpServletRequest request = (HttpServletRequest) messageInfo.getRequestMessage();
        HttpServletResponse response = (HttpServletResponse) messageInfo.getResponseMessage();
        String credentials = request.getHeader(HttpHeader.AUTHORIZATION.asString());

        try
        {
            if (credentials != null)
            {
                if (LOG.isDebugEnabled()) LOG.debug("Credentials: " + credentials);
                if (login(clientSubject, credentials, Constraint.__BASIC_AUTH, messageInfo)) { return AuthStatus.SUCCESS; }

            }

            if (!isMandatory(messageInfo)) { return AuthStatus.SUCCESS; }
            response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), "basic realm=\"" + realmName + '"');
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
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
}
