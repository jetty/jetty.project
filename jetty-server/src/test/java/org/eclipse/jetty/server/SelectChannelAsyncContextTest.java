package org.eclipse.jetty.server;

import java.net.Socket;

import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;

public class SelectChannelAsyncContextTest extends LocalAsyncContextTest
{

    @Override
    protected Connector initConnector()
    {
        return new SelectChannelConnector();
    }

    @Override
    protected String getResponse(String request) throws Exception
    {
        SelectChannelConnector connector = (SelectChannelConnector)_connector;
        Socket socket = new Socket((String)null,connector.getLocalPort());
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        return IO.toString(socket.getInputStream());
    }

}
