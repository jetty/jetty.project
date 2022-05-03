//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.http.spi;

import java.net.URL;
import javax.xml.namespace.QName;

import jakarta.jws.WebMethod;
import jakarta.jws.WebService;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebEndpoint;
import jakarta.xml.ws.WebServiceClient;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestEndpointMultiplePublishProblem
{
    static
    {
        LoggingUtil.init();
    }

    private static String default_impl = System.getProperty("com.sun.net.httpserver.HttpServerProvider");

    @BeforeAll
    public static void changeImpl()
    {
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", JettyHttpServerProvider.class.getName());
    }

    @AfterAll
    public static void restoreImpl()
    {
        if (default_impl != null)
        {
            System.setProperty("com.sun.net.httpserver.HttpServerProvider", default_impl);
        }
    }

    @Test
    public void mainJetty()
        throws Exception
    {

        Server jettyWebServer = new Server(new DelegatingThreadPool(new QueuedThreadPool()));
        ServerConnector connector = new ServerConnector(jettyWebServer);
        connector.setHost("localhost");
        connector.setPort(0);
        connector.setReuseAddress(true);
        jettyWebServer.addConnector(connector);
        jettyWebServer.setHandler(new ContextHandlerCollection());

        JettyHttpServerProvider.setServer(jettyWebServer);

        jettyWebServer.start();

        Endpoint.publish(String.format("http://%s:%d/hello", "localhost", 0), new WsHello());
        Endpoint.publish(String.format("http://%s:%d/hello2", "localhost", 0), new WsHello());

        int port = connector.getLocalPort();

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        {
            String url = String.format("http://localhost:%d/hello", port);
            String urlWsdl = url + "?wsdl";

            ContentResponse contentResponse = httpClient.newRequest(url).send();
            Assertions.assertEquals(200, contentResponse.getStatus());

            HelloMessengerService helloMessengerService = new HelloMessengerService(new URL(urlWsdl));
            Hello hello = helloMessengerService.getHelloMessengerPort();
            ((BindingProvider)hello).getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, url);
            String helloResponse = hello.hello();
            Assertions.assertEquals("G'Day mate!", helloResponse);
        }

        {

            String url2 = String.format("http://localhost:%d/hello2", port);
            String url2Wsdl = url2 + "?wsdl";

            ContentResponse contentResponse = httpClient.newRequest(url2Wsdl).send();
            Assertions.assertEquals(200, contentResponse.getStatus());

            HelloMessengerService helloMessengerService = new HelloMessengerService(new URL(url2Wsdl));
            Hello hello = helloMessengerService.getHelloMessengerPort();
            ((BindingProvider)hello).getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, url2);
            String helloResponse = hello.hello();
            Assertions.assertEquals("G'Day mate!", helloResponse);
        }
        httpClient.stop();
        jettyWebServer.stop();
    }

    @WebService(targetNamespace = "http://org.eclipse.jetty.ws.test", name = "HelloService")
    public interface Hello
    {
        @WebMethod
        String hello();
    }

    @WebService(targetNamespace = "http://org.eclipse.jetty.ws.test", name = "HelloService")
    public static class WsHello
        implements Hello
    {
        @WebMethod
        public String hello()
        {
            return "G'Day mate!";
        }
    }

    @WebServiceClient(name = "HelloService", targetNamespace = "http://org.eclipse.jetty.ws.test")
    public static class HelloMessengerService
        extends Service
    {

        public HelloMessengerService(URL wsdlLocation)
        {
            super(wsdlLocation, //
                new QName("http://org.eclipse.jetty.ws.test", "WsHelloService"));
        }

        @WebEndpoint(name = "HelloServicePort")
        public Hello getHelloMessengerPort()
        {
            return super.getPort(new QName("http://org.eclipse.jetty.ws.test", "HelloServicePort"), //
                Hello.class);
        }
    }

    private void assertWsdl(String wsdl)
        throws Exception
    {

    }
}
