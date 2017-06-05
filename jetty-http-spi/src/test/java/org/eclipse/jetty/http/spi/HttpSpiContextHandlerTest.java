//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.http.spi.util.SpiUtility;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.log.Log;
import org.junit.Before;
import org.junit.Test;
import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Authenticator.Result;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;

public class HttpSpiContextHandlerTest
{

    private HttpSpiContextHandler httpSpiContextHandler;

    private HttpContext httpContext;

    private HttpHandler httpHandler;

    private Request baseRequest;

    private HttpServletRequest req;

    private HttpServletResponse resp;

    private HttpExchange httpExchange;

    private Authenticator auth;

    @Before
    public void setUp() throws Exception
    {
        httpContext = mock(HttpContext.class);
        httpHandler = mock(HttpHandler.class);
        httpSpiContextHandler = new HttpSpiContextHandler(httpContext,httpHandler);
    }

    @Test
    public void testGetSetHttpHandler()
    {
        // given
        httpHandler = mock(HttpHandler.class);

        // when
        httpSpiContextHandler.setHttpHandler(httpHandler);

        // then
        assertEquals("HttpHandler instances must be equal.",httpHandler,httpSpiContextHandler.getHttpHandler());
    }

    @Test
    public void testDoScopeWithNoHandler() throws Exception
    {
        // given
        setUpDoScope();

        // when
        httpSpiContextHandler.doScope("test",baseRequest,req,resp);

        // then
        assertFalse("Context path doesn't start with /, so none of the handler will handle the request",baseRequest.isHandled());
    }

    @Test
    public void testDoScopeHttpExchangeHandler() throws Exception
    {
        // given
        setUpDoScope();

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(httpHandler).handle((JettyHttpExchange)anyObject());
    }

    @Test
    public void testDoScopeHttpsExchangeHandler() throws Exception
    {
        // given
        setUpDoScope();
        when(baseRequest.isSecure()).thenReturn(true);

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(httpHandler).handle((JettyHttpsExchange)anyObject());
    }

    @Test
    public void testDoScopeAuthenticationHandlerFailure() throws Exception
    {
        // given
        Authenticator.Result failure = new Authenticator.Failure(SpiConstants.FAILURE_STATUS);
        setUpAuthentication(failure);

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(resp).sendError(SpiConstants.FAILURE_STATUS);
    }

    @Test
    public void testDoScopeAuthenticationHandlerSuccess() throws Exception
    {
        // given
        HttpPrincipal principle = mock(HttpPrincipal.class);
        Authenticator.Result retry = new Authenticator.Success(principle);
        setUpSuccessAuthentication(retry);

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(httpHandler).handle((JettyHttpExchange)anyObject());
    }

    @Test
    public void testDoScopeAuthenticationHandlerRetry() throws Exception
    {
        // given
        Authenticator.Result retry = new Authenticator.Retry(SpiConstants.RETRY_STATUS);
        setUpRetryAuthentication(retry);

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(resp).setStatus(SpiConstants.RETRY_STATUS);
    }

    @Test
    public void testDoScopeExceptionWithLoggerEnable() throws Exception
    {
        // given
        setUpAuthenticationException();
        Log.getRootLogger().setDebugEnabled(true);

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(resp).setStatus(SpiConstants.FAILURE_STATUS);
    }

    @Test
    public void testDoScopeExceptionWithLoggerDisable() throws Exception
    {
        // given
        setUpAuthenticationException();

        // when
        httpSpiContextHandler.doScope("/test",baseRequest,req,resp);

        // then
        verify(resp).setStatus(SpiConstants.FAILURE_STATUS);
    }

    private void setUpDoScope() throws Exception
    {
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        baseRequest = mock(Request.class);
    }

    private void setUpAuthenticationException() throws Exception
    {
        setUpDoScope();
        ServletOutputStream printStream = mock(ServletOutputStream.class);
        when(resp.getOutputStream()).thenReturn(printStream);
        HttpChannel httpChannel = mock(HttpChannel.class);
        when(baseRequest.getHttpChannel()).thenReturn(httpChannel);
        HttpConfiguration configuration = mock(HttpConfiguration.class);
        when(httpChannel.getHttpConfiguration()).thenReturn(configuration);
        doThrow(new RuntimeException()).when(httpContext).getAuthenticator();
    }

    private void setUpAuthentication(Result result) throws Exception
    {
        setUpDoScope();
        httpExchange = mock(HttpExchange.class);
        auth = mock(Authenticator.class);
        Map<String, List<String>> reqHeaders = new HashMap<>();
        reqHeaders.put("accepted-Language",SpiUtility.getReqHeaderValues());
        Headers headers = new Headers();
        headers.putAll(reqHeaders);
        when(httpExchange.getResponseHeaders()).thenReturn(headers);
        when(auth.authenticate(anyObject())).thenReturn(result);
        when(httpContext.getAuthenticator()).thenReturn(auth);
    }

    private void setUpRetryAuthentication(Result result) throws Exception
    {
        setUpAuthentication(result);
        when(req.getAttribute(SpiConstants.USER_NAME)).thenReturn(SpiConstants.VALID_USER);
    }

    private void setUpSuccessAuthentication(Result result) throws Exception
    {
        setUpAuthentication(result);
        when(req.getAttribute(SpiConstants.USER_NAME)).thenReturn(SpiConstants.VALID_USER);
        when(req.getAttribute(SpiConstants.PASSWORD)).thenReturn(SpiConstants.VALID_PASSWORD);
    }
}
