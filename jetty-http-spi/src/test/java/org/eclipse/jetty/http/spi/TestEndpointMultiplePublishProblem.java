//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.URL;
import javax.jws.WebMethod;
import javax.jws.WebService;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Endpoint;
import javax.xml.ws.Service;
import javax.xml.ws.WebEndpoint;
import javax.xml.ws.WebServiceClient;

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
