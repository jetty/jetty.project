package org.eclipse.jetty.test;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;

public class TestClass
{
    private Server _server;
    private HttpClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server(8080);

        URL webRootLocation = TestClass.class.getClassLoader().getResource("webroot/index.html");
        URI webRootUri = URI.create(webRootLocation.toURI().toASCIIString().replaceFirst("/index.html$","/"));
        System.err.printf("Web Root URI: %s%n",webRootUri);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setBaseResource(Resource.newResource(webRootUri));
        context.setWelcomeFiles(new String[] { "index.html" });
        context.setProtectedTargets(new String[]{"/web-inf", "/meta-inf"});
        context.getMimeTypes().addMimeMapping("txt","text/plain;charset=utf-8");

        context.addAliasCheck(new AllowedResourceAliasChecker(context));
        _server.setHandler(context);
        context.addServlet(DefaultServlet.class,"/");
        _server.start();

        _client = new HttpClient();
        _client.start();
    }


    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
//        ContentResponse response = _client.GET("http://localhost:8080/\\WEB-INF\\web.xml");
//        System.err.println(response);

        _server.join();
    }
}
