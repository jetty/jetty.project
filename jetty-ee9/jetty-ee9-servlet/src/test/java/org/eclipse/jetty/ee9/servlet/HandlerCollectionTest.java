package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.HandlerCollection;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class HandlerCollectionTest
{
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    private void startServer(Handler coreHandler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);
        server.setHandler(coreHandler);
        server.start();
    }

    @Test
    public void testHandlerCollection() throws Exception
    {
        // ee9 specific nested HandlerCollection
        HandlerCollection ee9HandlerCollection = new HandlerCollection();
        ServletContextHandler ee9ContextHandler = new ServletContextHandler();
        ee9ContextHandler.setContextPath("/test");

        ServletHolder ee9ServletHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                resp.getWriter().println("Got GET Request");
            }
        });
        ee9ContextHandler.addServlet(ee9ServletHolder, "/info");

        ee9HandlerCollection.addHandler(ee9ContextHandler);
        ContextHandler ee9RootContext = new ContextHandler("/", ee9HandlerCollection);

        // Core ContextHandlerCollection
        ContextHandlerCollection coreContextHandlerCollection = new ContextHandlerCollection();
        coreContextHandlerCollection.addHandler(ee9RootContext);

        startServer(coreContextHandlerCollection);

        String rawRequest = """
            GET /test/info HTTP/1.1
            Host: local
            Connection: close
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(localConnector.getResponse(rawRequest));
        assertThat("status", response.getStatus(), is(200));
        assertThat("response content", response.getContent(), containsString("Got GET Request"));
    }
}
