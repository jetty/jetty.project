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

import java.lang.reflect.Proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JaspiMessageInfoTest
{
    private final HttpServletRequest _request = stub(HttpServletRequest.class);
    private final HttpServletResponse _response = stub(HttpServletResponse.class);

    /**
     * @return an instance of the given message type, which these tests only ever identify.
     */
    private static <T> T stub(Class<T> type)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null));
    }

    @Test
    public void testMandatoryKeyIsOnlyPresentWhenMandatory()
    {
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(_request, _response, true);
        assertThat(messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY), equalTo("true"));

        messageInfo.setMandatory(false);
        assertFalse(messageInfo.getMap().containsKey(JaspiMessageInfo.MANDATORY_KEY));

        messageInfo.setMandatory(true);
        assertThat(messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY), equalTo("true"));
    }

    @Test
    public void testAuthenticationTypeIsWhateverTheModulePut()
    {
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(_request, _response, false);
        assertThat(messageInfo.getAuthenticationType(), nullValue());

        messageInfo.getMap().put(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY, "FORM");
        assertThat(messageInfo.getAuthenticationType(), equalTo("FORM"));

        messageInfo.getMap().remove(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY);
        assertThat(messageInfo.getAuthenticationType(), nullValue());
    }

    @Test
    public void testRegisterSessionIsAnyStringBooleanValueOfAcceptsAsTrue()
    {
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(_request, _response, false);
        assertFalse(messageInfo.isRegisterSession());

        messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "TRUE");
        assertTrue(messageInfo.isRegisterSession());

        messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "false");
        assertFalse(messageInfo.isRegisterSession());

        // Only a String value counts, so a Boolean does not.
        messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, Boolean.TRUE);
        assertFalse(messageInfo.isRegisterSession());
    }

    @Test
    public void testTheMessagesAreWhatTheModuleReplacedThemWith()
    {
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(_request, _response, false);
        assertThat(messageInfo.getRequestMessage(), sameInstance(_request));
        assertThat(messageInfo.getResponseMessage(), sameInstance(_response));

        HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(_request);
        HttpServletResponseWrapper wrappedResponse = new HttpServletResponseWrapper(_response);
        messageInfo.setRequestMessage(wrappedRequest);
        messageInfo.setResponseMessage(wrappedResponse);
        assertThat(messageInfo.getRequestMessage(), sameInstance(wrappedRequest));
        assertThat(messageInfo.getResponseMessage(), sameInstance(wrappedResponse));
    }

    @Test
    public void testAMessageThatIsNotAnHttpServletMessageIsRejected()
    {
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(_request, _response, false);
        assertThrows(IllegalStateException.class, () -> messageInfo.setRequestMessage("not a request"));
        assertThrows(IllegalStateException.class, () -> messageInfo.setResponseMessage("not a response"));
        assertThat(messageInfo.getRequestMessage(), sameInstance(_request));
        assertThat(messageInfo.getResponseMessage(), sameInstance(_response));
    }
}
