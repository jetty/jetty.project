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

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.util.log.Log;

/**
 * @deprecated use *ServerAuthentication
 * @version $Rev: 4660 $ $Date: 2009-02-25 17:29:53 +0100 (Wed, 25 Feb 2009) $
 */
public class BasicAuthModule extends BaseAuthModule
{

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
        String credentials = request.getHeader(HttpHeaders.AUTHORIZATION);

        try
        {
            if (credentials != null)
            {
                if (Log.isDebugEnabled()) Log.debug("Credentials: " + credentials);
                if (login(clientSubject, credentials, Constraint.__BASIC_AUTH, messageInfo)) { return AuthStatus.SUCCESS; }

            }

            if (!isMandatory(messageInfo)) { return AuthStatus.SUCCESS; }
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "basic realm=\"" + realmName + '"');
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
