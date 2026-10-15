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

package org.eclipse.jetty.ee.security.jaspi;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JaspiMessageInfoTest
{
    private Server _server;
    private HttpServletRequest _request;
    private HttpServletResponse _response;
    private JaspiMessageInfo _messageInfo;

    /**
     * The messages come from a request the server has finished with, as a {@code JaspiMessageInfo}
     * can only be made from a {@link ServletContextRequest}.
     */
    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        LocalConnector connector = new LocalConnector(_server);
        _server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/ctx");
        context.addServlet(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
            {
                ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
                _messageInfo = new JaspiMessageInfo(servletContextRequest, servletContextRequest.getServletContextResponse());
                _request = request;
                _response = response;
            }
        }, "/");
        _server.setHandler(context);
        _server.start();

        assertThat(connector.getResponse("GET /ctx/resource HTTP/1.0\r\n\r\n"), startsWith("HTTP/1.1 200"));
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testMandatoryKeyIsOnlyPresentWhenMandatory()
    {
        _messageInfo.setMandatory(true);
        assertThat(_messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY), equalTo("true"));

        _messageInfo.setMandatory(false);
        assertFalse(_messageInfo.getMap().containsKey(JaspiMessageInfo.MANDATORY_KEY));
    }

    @Test
    public void testAuthenticationTypeIsWhateverTheModulePut()
    {
        assertThat(_messageInfo.getAuthenticationType(), nullValue());

        _messageInfo.getMap().put(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY, "FORM");
        assertThat(_messageInfo.getAuthenticationType(), equalTo("FORM"));

        _messageInfo.getMap().remove(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY);
        assertThat(_messageInfo.getAuthenticationType(), nullValue());
    }

    @Test
    public void testRegisterSessionIsAnyStringBooleanValueOfAcceptsAsTrue()
    {
        assertFalse(_messageInfo.isRegisterSession());

        _messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "TRUE");
        assertTrue(_messageInfo.isRegisterSession());

        _messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "false");
        assertFalse(_messageInfo.isRegisterSession());

        // Only a String value counts, so a Boolean does not.
        _messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, Boolean.TRUE);
        assertFalse(_messageInfo.isRegisterSession());
    }

    @Test
    public void testTheMessagesAreWhatTheModuleReplacedThemWith()
    {
        assertThat(_messageInfo.getRequestMessage(), sameInstance(_request));
        assertThat(_messageInfo.getResponseMessage(), sameInstance(_response));

        HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(_request);
        HttpServletResponseWrapper wrappedResponse = new HttpServletResponseWrapper(_response);
        _messageInfo.setRequestMessage(wrappedRequest);
        _messageInfo.setResponseMessage(wrappedResponse);
        assertThat(_messageInfo.getRequestMessage(), sameInstance(wrappedRequest));
        assertThat(_messageInfo.getResponseMessage(), sameInstance(wrappedResponse));
    }

    @Test
    public void testAMessageThatIsNotAnHttpServletMessageIsRejected()
    {
        assertThrows(IllegalStateException.class, () -> _messageInfo.setRequestMessage("not a request"));
        assertThrows(IllegalStateException.class, () -> _messageInfo.setResponseMessage("not a response"));
        assertThat(_messageInfo.getRequestMessage(), sameInstance(_request));
        assertThat(_messageInfo.getResponseMessage(), sameInstance(_response));
    }
}
