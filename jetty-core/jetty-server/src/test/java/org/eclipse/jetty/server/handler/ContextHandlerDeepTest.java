package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContextHandlerDeepTest
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
    public void testNestedThreeDeepContextHandler() throws Exception
    {
        ContextHandler contextHandlerA = new ContextHandler();
        contextHandlerA.setContextPath("/a");
        ContextHandler contextHandlerB = new ContextHandler();
        contextHandlerB.setContextPath("/b");
        ContextHandler contextHandlerC = new ContextHandler();
        contextHandlerC.setContextPath("/c");

        contextHandlerA.setHandler(contextHandlerB);
        contextHandlerB.setHandler(contextHandlerC);
        contextHandlerC.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                String msg = """
                    contextPath=%s
                    pathInContext=%s
                    httpURI.getPath=%s
                    """
                    .formatted(
                        request.getContext().getContextPath(),
                        request.getPathInContext(),
                        request.getHttpURI().getPath()
                    );

                response.write(true, BufferUtil.toBuffer(msg), callback);
            }
        });

        startServer(contextHandlerA);

        String rawRequest = """
            GET /a/b/c/d HTTP/1.1\r
            Host: local\r
            Connection: close\r
                        
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), containsString("contextPath=/a/b/c\n"));
        assertThat(response.getContent(), containsString("pathInContext=/d\n"));
        assertThat(response.getContent(), containsString("httpURI.getPath=/a/b/c/d\n"));
    }
}
