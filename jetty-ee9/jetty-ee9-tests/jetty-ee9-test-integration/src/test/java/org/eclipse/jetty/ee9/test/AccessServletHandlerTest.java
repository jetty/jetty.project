package org.eclipse.jetty.ee9.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AccessServletHandlerTest
{

    private Server server;
    private LocalConnector connector;

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testAccessServletHandler() throws Exception
    {
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();

        HttpServlet handlerWalkServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                Server server = (Server)req.getAttribute(Server.class.getName());
                List<ServletHandler> servletHandlers = getServletHandlers(server);

                resp.setStatus(servletHandlers.isEmpty() ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : HttpServletResponse.SC_OK);
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");

                PrintWriter out = resp.getWriter();
                out.printf("Found %d servletHandler(s)%n", servletHandlers.size());
                for (ServletHandler servletHandler: servletHandlers)
                {
                    out.println(servletHandler.dump());
                }

                for (ContextHandlerCollection chc: server.getDescendants(ContextHandlerCollection.class))
                {
                    out.println(chc.dump());
                }
            }

            private List<ServletHandler> getServletHandlers(Server server)
            {
                List<ServletHandler> servletHandlers = new ArrayList<>();

                // The WebAppContext core references
                List<ContextHandler.CoreContextHandler> webappCores = server.getDescendants(ContextHandler.CoreContextHandler.class);
                for (ContextHandler.CoreContextHandler webappCore: webappCores)
                {
                    // The WebAppContext
                    WebAppContext webappContext = webappCore.getBean(WebAppContext.class);
                    servletHandlers.add(webappContext.getChildHandlerByClass(ServletHandler.class));
                }
                return servletHandlers;
            }
        };

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/foo");
        webapp.setBaseResourceAsPath(MavenPaths.targetTests());

        ServletHolder handlerWalker = new ServletHolder(handlerWalkServlet);
        webapp.addServlet(handlerWalker, "/handlers");

        contextHandlerCollection.addHandler(webapp);

        startServer(contextHandlerCollection);

        String rawRequest = """
            GET /foo/handlers HTTP/1.1
            Host: local
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("Found 1 servletHandler(s)"));
        assertThat(response.getContent(), containsString(handlerWalkServlet.getClass().getName() + "@"));
        // System.out.println(response.getContent());
    }
}
