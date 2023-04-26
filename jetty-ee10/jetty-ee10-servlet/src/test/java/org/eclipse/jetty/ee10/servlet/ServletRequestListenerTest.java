package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ServletRequestListenerTest
{
    private final List<String> _events = new ArrayList<>();
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _httpClient;

    public void start(HttpServlet servlet) throws Exception
    {
        start(contextHandler -> contextHandler.addServlet(servlet, "/"));
    }

    public void start(Consumer<ServletContextHandler> configuration) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.addEventListener(new TestRequestListener());
        contextHandler.addFilter(new TestFilter(), "/*", EnumSet.allOf(DispatcherType.class));
        configuration.accept(contextHandler);

        _server.setHandler(contextHandler);
        _server.start();

        _httpClient = new HttpClient();
        _httpClient.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _httpClient.stop();
        _server.stop();
    }

    @Test
    public void testForward() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
                _events.add(req.getDispatcherType() + " " + pathInContext);
                if (req.getDispatcherType() == DispatcherType.REQUEST)
                {
                    req.getRequestDispatcher(pathInContext).forward(req, resp);
                    return;
                }

                resp.getWriter().print("success");
            }
        });

        ContentResponse response = _httpClient.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("success"));

        assertEvents("requestInitialized /", "doFilter /", "REQUEST /", "doFilter /", "FORWARD /", "requestDestroyed /");
    }

    @Test
    public void testInclude() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
                _events.add(req.getDispatcherType() + " " + pathInContext);
                if (req.getDispatcherType() == DispatcherType.REQUEST)
                {
                    req.getRequestDispatcher(pathInContext).include(req, resp);
                    return;
                }

                resp.getWriter().print("success");
            }
        });

        ContentResponse response = _httpClient.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("success"));

        assertEvents("requestInitialized /", "doFilter /", "REQUEST /", "doFilter /", "INCLUDE /", "requestDestroyed /");
    }

    @Test
    public void testAsync() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
                _events.add(req.getDispatcherType() + " " + pathInContext);
                if (req.getDispatcherType() == DispatcherType.REQUEST)
                {
                    AsyncContext asyncContext = req.startAsync();
                    asyncContext.dispatch();
                    return;
                }

                if (req.isAsyncStarted())
                    req.getAsyncContext().complete();
                resp.getWriter().print("success");
            }
        });

        ContentResponse response = _httpClient.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), equalTo("success"));

        assertEvents("requestInitialized /", "doFilter /", "REQUEST /", "requestDestroyed /",
            "requestInitialized /", "doFilter /", "ASYNC /", "requestDestroyed /");
    }


    @Test
    public void testError() throws Exception
    {
        start(contextHandler ->
        {
            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.addErrorPage(500, "/error");
            contextHandler.setErrorHandler(errorHandler);

            contextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
                    _events.add(req.getDispatcherType() + " " + pathInContext);
                    if (req.getDispatcherType() == DispatcherType.REQUEST)
                    {
                        resp.sendError(500);
                        return;
                    }

                    resp.setStatus(500);
                    resp.getWriter().print("error handled");
                }
            }, "/");
        });

        ContentResponse response = _httpClient.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), equalTo("error handled"));

        assertEvents("requestInitialized /", "doFilter /", "REQUEST /", "requestDestroyed /",
            "requestInitialized /", "doFilter /error", "ERROR /error", "requestDestroyed /");
    }

    @Test
    public void testErrorNonDispatched() throws Exception
    {
        start(contextHandler ->
        {
            contextHandler.setErrorHandler((request, response, callback) ->
            {
                response.setStatus(500);
                response.write(true, BufferUtil.toBuffer("error handled"), callback);
                _events.add("errorHandler");
                return true;
            });

            contextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
                    _events.add(req.getDispatcherType() + " " + pathInContext);
                    if (req.getDispatcherType() == DispatcherType.REQUEST)
                    {
                        resp.sendError(500);
                        return;
                    }

                    throw new IllegalStateException("should not reach here");
                }
            }, "/");
        });

        ContentResponse response = _httpClient.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), equalTo("error handled"));

        assertEvents("requestInitialized /", "doFilter /", "REQUEST /", "requestDestroyed /", "errorHandler");
    }

    public class TestRequestListener implements ServletRequestListener
    {
        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            HttpServletRequest req = (HttpServletRequest)sre.getServletRequest();
            String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
            _events.add("requestInitialized " + pathInContext);
        }

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            HttpServletRequest req = (HttpServletRequest)sre.getServletRequest();
            String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
            _events.add("requestDestroyed " + pathInContext);
        }
    }

    public class TestFilter implements Filter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            HttpServletRequest req = (HttpServletRequest)request;
            String pathInContext = URIUtil.addPaths(req.getContextPath(), req.getServletPath());
            _events.add("doFilter " + pathInContext);
            chain.doFilter(request, response);
        }
    }

    private void assertEvents(String... events)
    {
        assertThat(_events, equalTo(Arrays.asList(events)));
    }
}
