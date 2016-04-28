//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.jaspi.JaspiMessageInfo;
import org.eclipse.jetty.security.jaspi.callback.CredentialValidationCallback;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.security.Credential;
import org.junit.Test;

public class BaseAuthModuleTest
{

    private ServletRequest request;

    private ServletResponse response;

    private Boolean isAuthMandatory = true;

    private Map options;

    private MessagePolicy responsePolicy;

    private MessagePolicy requestPolicy;

    private MessageInfo messageInfo;

    private CallbackHandler callbackHandler;

    private BaseAuthModule baseAuthModule;

    private String credentials;

    private String authMethod;

    private Class[] supportedMessageTypes;

    @Test
    public void testGetSupportedMessageTypes()
    {
        // given
        baseAuthModule = new BaseAuthModule();

        // when
        supportedMessageTypes = baseAuthModule.getSupportedMessageTypes();

        // then
        assertEquals("Supported message types length should be 2", 2, supportedMessageTypes.length);
        assertEquals("Supported message type contains HttpServletRequest at first position", HttpServletRequest.class, supportedMessageTypes[0]);
        assertEquals("Supported message type contains HttpServletResponse at second position ", HttpServletResponse.class, supportedMessageTypes[1]);
    }

    @Test
    public void testConstructorWithArgs() 
    {
        // when
        callbackHandler = getCallBackHandler();
        baseAuthModule = new BaseAuthModule(callbackHandler);

        // then
        assertEquals("Constructor populates the callbackhandler property", callbackHandler, baseAuthModule.callbackHandler);
    }

    @Test
    public void testCleanSubject() throws AuthException
    {
        // given
        baseAuthModule = new BaseAuthModule();
        BaseAuthModule spy = spy(baseAuthModule);
        doNothing().when(spy).cleanSubject(null, null);

        // Executing a no-op method to ensure that no exceptions are thrown
        baseAuthModule.cleanSubject(null, null);
    }

    @Test
    public void testValidateRequest() throws AuthException
    {
        // given
        callbackHandler = getCallBackHandler();
        setUpMessageInfoAndAuthModule(callbackHandler, true);

        // when
        AuthStatus expectedStatus = baseAuthModule.validateRequest(messageInfo, new Subject(), new Subject());

        // then
        assertEquals("Actual code is implemented in such a way that, it should always return SEND_FAILURE status", AuthStatus.SEND_FAILURE, expectedStatus);
    }

    @Test
    public void testSecureResponse() throws AuthException
    {
        // given
        callbackHandler = getCallBackHandler();
        setUpMessageInfoAndAuthModule(callbackHandler,true);

        // when
        AuthStatus expectedStatus = baseAuthModule.secureResponse(messageInfo, new Subject());

        // then
        assertEquals("Actual code is implemented in such a way that, it should always return SEND_SUCCESS status", AuthStatus.SEND_SUCCESS, expectedStatus);
    }

    @Test
    public void testLogin() throws UnsupportedCallbackException,IOException
    {
        // given
        credentials = B64Code.encode("username:password");
        authMethod = "BASIC";
        Set<Object> sets = new HashSet<Object>();
        sets.add(new Object());
        Subject clientSubject = new Subject();
        clientSubject.getPrivateCredentials().add(sets);
        callbackHandler = getCallBackHandlerWithHandlers();
        setUpMessageInfoAndAuthModule(callbackHandler, false);

        // when
        Boolean result = baseAuthModule.login(clientSubject, credentials, authMethod,messageInfo);

        // then
        assertTrue("As credentials are correct,login method must return true", result);
    }

    @Test
    public void testIsMandatory()
    {
        // given
        callbackHandler = getCallBackHandler();
        messageInfo = new JaspiMessageInfo(request, response, isAuthMandatory);

        // when
        baseAuthModule = new BaseAuthModule(callbackHandler);

        // then
        assertTrue("As mandatory attribute sets to true, this must return true", baseAuthModule.isMandatory(messageInfo));
    }

    @Test
    public void testInitialize() throws AuthException
    {
        // given
        options = new HashMap();
        callbackHandler = getCallBackHandlerWithHandlers();
        setUpMessageInfoAndAuthModule(callbackHandler,false);

        // when
        baseAuthModule.initialize(requestPolicy, responsePolicy, callbackHandler, options);

        // then
        assertEquals("Callback handler instances should match", callbackHandler, baseAuthModule.callbackHandler);
        assertThat("MessageInfo must be of type JaspiMessageInfo", messageInfo.getClass(), is(instanceOf(JaspiMessageInfo.class.getClass())));
    }

    private void setUpMessageInfoAndAuthModule(CallbackHandler callbackHandler, Boolean mockFlag)
    {
        if (mockFlag)
        {
            messageInfo = mock(MessageInfo.class);

        }
        else
        {
            messageInfo = new JaspiMessageInfo(request, response, isAuthMandatory);
        }
        baseAuthModule = new BaseAuthModule(callbackHandler);
    }

    private CallbackHandler getCallBackHandler()
    {
        CallbackHandler callbackHandler = new CallbackHandler()
        {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
            {
                // A no op call back implementaion
            }
        };
        return callbackHandler;
    }

    private CallbackHandler getCallBackHandlerWithHandlers()
    {
        CallbackHandler callbackHandler = new CallbackHandler()
        {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException
            {
                for (Callback callback : callbacks)
                {
                    CredentialValidationCallback credValidationCallback = (CredentialValidationCallback)callback;
                    String userName = credValidationCallback.getUsername();
                    Credential credential = credValidationCallback.getCredential();
                    Credential expectedPassword = Credential.getCredential("password");
                    if ("username".equals(userName) && expectedPassword.equals(credential))
                    {
                        credValidationCallback.setResult(true);
                    }
                    else
                    {
                        credValidationCallback.setResult(false);
                    }
                }
            }
        };
        return callbackHandler;
    }
}