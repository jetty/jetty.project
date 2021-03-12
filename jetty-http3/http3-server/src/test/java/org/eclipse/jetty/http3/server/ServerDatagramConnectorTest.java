package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

public class ServerDatagramConnectorTest
{
    @Test
    public void name() throws Exception
    {
        Server server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory(config);

        ServerDatagramConnector serverDatagramConnector = new ServerDatagramConnector(server, connectionFactory);
        serverDatagramConnector.setPort(8443);
        server.addConnector(serverDatagramConnector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                PrintWriter writer = response.getWriter();
                writer.println("<html>\n" +
                    "\t<body>\n" +
                    "\t\tRequest served\n" +
                    "\t</body>\n" +
                    "</html>");
            }
        });

        server.start();

        System.out.println("Started.");
        System.in.read();

        server.stop();
    }
}
