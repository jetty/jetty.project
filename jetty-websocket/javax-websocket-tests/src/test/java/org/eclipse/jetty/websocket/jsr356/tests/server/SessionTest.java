//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.server;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.jsr356.tests.Fuzzer;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SessionTest
{
    @ServerEndpoint(value = "/info/")
    public static class SessionInfoSocket
    {
        @OnMessage
        public String onMessage(javax.websocket.Session session, String message)
        {
            if ("pathParams".equalsIgnoreCase(message))
            {
                StringBuilder ret = new StringBuilder();
                ret.append("pathParams");
                Map<String, String> pathParams = session.getPathParameters();
                if (pathParams == null)
                {
                    ret.append("=<null>");
                }
                else
                {
                    ret.append('[').append(pathParams.size()).append(']');
                    List<String> keys = new ArrayList<>();
                    for (String key : pathParams.keySet())
                    {
                        keys.add(key);
                    }
                    Collections.sort(keys);
                    for (String key : keys)
                    {
                        String value = pathParams.get(key);
                        ret.append(": '").append(key).append("'=").append(value);
                    }
                }
                return ret.toString();
            }
            
            if ("requestUri".equalsIgnoreCase(message))
            {
                StringBuilder ret = new StringBuilder();
                ret.append("requestUri=");
                URI uri = session.getRequestURI();
                if (uri == null)
                {
                    ret.append("=<null>");
                }
                else
                {
                    ret.append(uri.toASCIIString());
                }
                return ret.toString();
            }
            
            // simple echo
            return "echo:'" + message + "'";
        }
    }
    
    public static class SessionInfoEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        private javax.websocket.Session session;
        
        @Override
        public void onOpen(javax.websocket.Session session, EndpointConfig config)
        {
            this.session = session;
            this.session.addMessageHandler(this);
        }
        
        @Override
        public void onMessage(String message)
        {
            try
            {
                if ("pathParams".equalsIgnoreCase(message))
                {
                    StringBuilder ret = new StringBuilder();
                    ret.append("pathParams");
                    Map<String, String> pathParams = session.getPathParameters();
                    if (pathParams == null)
                    {
                        ret.append("=<null>");
                    }
                    else
                    {
                        ret.append('[').append(pathParams.size()).append(']');
                        List<String> keys = new ArrayList<>();
                        for (String key : pathParams.keySet())
                        {
                            keys.add(key);
                        }
                        Collections.sort(keys);
                        for (String key : keys)
                        {
                            String value = pathParams.get(key);
                            ret.append(": '").append(key).append("'=").append(value);
                        }
                    }
                    session.getBasicRemote().sendText(ret.toString());
                    return;
                }
                
                if ("requestUri".equalsIgnoreCase(message))
                {
                    StringBuilder ret = new StringBuilder();
                    ret.append("requestUri=");
                    URI uri = session.getRequestURI();
                    if (uri == null)
                    {
                        ret.append("=<null>");
                    }
                    else
                    {
                        ret.append(uri.toASCIIString());
                    }
                    session.getBasicRemote().sendText(ret.toString());
                    return;
                }
                
                // simple echo
                session.getBasicRemote().sendText("echo:'" + message + "'");
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
    }
    
    private static class Case
    {
        public final String description;
        public Consumer<ServletContextHandler> customizer;

        public Case(String description, Consumer<ServletContextHandler> customizer)
        {
            this.description = description;
            this.customizer = customizer;
        }

        @Override
        public String toString()
        {
            return this.description;
        }
    }

    private static class Cases extends ArrayList<Case[]>
    {
        public void addCase(String description, Consumer<ServletContextHandler> customizer)
        {
            this.add(new Case[]{new Case(description, customizer)});
        }
    }

    @Parameters(name="{0}")
    public static Collection<Case[]> data()
    {
        Cases cases = new Cases();
        cases.addCase("Default ServletContextHandler", context -> { });
        cases.addCase("With DefaultServlet only", context -> context.addServlet(DefaultServlet.class, "/"));
        cases.addCase("With Servlet Mapped to '/*'", context -> context.addServlet(DefaultServlet.class, "/*"));
        cases.addCase("With Servlet Mapped to '/info/*'", context -> context.addServlet(DefaultServlet.class, "/info/*"));
        return cases;
    }
    
    private LocalServer server;

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    public SessionTest(final Case testcase) throws Exception
    {
        server = new LocalServer();
        server.start();
        testcase.customizer.accept(server.getServletContextHandler());
        ServerContainer container = server.getServerContainer();
        container.addEndpoint(SessionInfoSocket.class); // default behavior
        Class<?> endpointClass = SessionInfoSocket.class;
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/{b}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/{b}/{c}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/{b}/{c}/{d}/").build());
        /*
        endpointClass = SessionInfoEndpoint.class;
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/{b}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/{b}/{c}/").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/{b}/{c}/{d}/").build());
        */
    }
    
    private void assertResponse(String requestPath, String requestMessage,
                                String expectedResponse) throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(requestMessage));
        send.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(expectedResponse));
        expect.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
    
        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @Test
    public void testPathParams_Annotated_Empty() throws Exception
    {
        assertResponse("/info/","pathParams",
                "pathParams[0]");
    }

    @Test
    public void testPathParams_Annotated_Single() throws Exception
    {
        assertResponse("/info/apple/","pathParams",
                "pathParams[1]: 'a'=apple");
    }

    @Test
    public void testPathParams_Annotated_Double() throws Exception
    {
        assertResponse("/info/apple/pear/","pathParams",
                "pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @Test
    public void testPathParams_Annotated_Triple() throws Exception
    {
        assertResponse("/info/apple/pear/cherry/","pathParams",
                "pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @Test
    @Ignore
    public void testPathParams_Endpoint_Empty() throws Exception
    {
        assertResponse("/einfo/","pathParams",
                "pathParams[0]");
    }

    @Test
    @Ignore
    public void testPathParams_Endpoint_Single() throws Exception
    {
        assertResponse("/einfo/apple/","pathParams",
                "pathParams[1]: 'a'=apple");
    }

    @Test
    @Ignore
    public void testPathParams_Endpoint_Double() throws Exception
    {
        assertResponse("/einfo/apple/pear/","pathParams",
                "pathParams[2]: 'a'=apple: 'b'=pear");
    }

    @Test
    @Ignore
    public void testPathParams_Endpoint_Triple() throws Exception
    {
        assertResponse("/einfo/apple/pear/cherry/","pathParams",
                "pathParams[3]: 'a'=apple: 'b'=pear: 'c'=cherry");
    }

    @Test
    public void testRequestUri_Annotated_Basic() throws Exception
    {
        assertResponse("/info/","requestUri",
                "requestUri=" + server.getWsUri().toASCIIString() + "info/");
    }

    @Test
    public void testRequestUri_Annotated_WithPathParam() throws Exception
    {
        assertResponse("/info/apple/banana/","requestUri",
                "requestUri=" + server.getWsUri().toASCIIString() +
                        "info/apple/banana/");
    }

    @Test
    public void testRequestUri_Annotated_WithPathParam_WithQuery() throws Exception
    {
        assertResponse("/info/apple/banana/?fruit=fresh&store=grandmasfarm",
                "requestUri",
                "requestUri=" + server.getWsUri().toASCIIString() +
                        "info/apple/banana/?fruit=fresh&store=grandmasfarm");
    }

    @Test
    @Ignore
    public void testRequestUri_Endpoint_Basic() throws Exception
    {
        assertResponse("/einfo/","requestUri",
                "requestUri=" + server.getWsUri().toASCIIString() + "einfo/");
    }

    @Test
    @Ignore
    public void testRequestUri_Endpoint_WithPathParam() throws Exception
    {
        assertResponse("/einfo/apple/banana/","requestUri",
                "requestUri=" + server.getWsUri().toASCIIString() + "einfo/apple/banana/");
    }

    @Test
    @Ignore
    public void testRequestUri_Endpoint_WithPathParam_WithQuery() throws Exception
    {
        assertResponse("/einfo/apple/banana/?fruit=fresh&store=grandmasfarm",
                "requestUri",
                "requestUri=" + server.getWsUri().toASCIIString() +
                        "einfo/apple/banana/?fruit=fresh&store=grandmasfarm");
    }
}
