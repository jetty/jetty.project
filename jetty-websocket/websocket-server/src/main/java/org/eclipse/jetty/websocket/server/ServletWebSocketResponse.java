package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class ServletWebSocketResponse extends HttpServletResponseWrapper implements WebSocketResponse
{
    private String acceptedProtocol;
    private List<ExtensionConfig> extensions = new ArrayList<>();

    public ServletWebSocketResponse(HttpServletResponse resp)
    {
        super(resp);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return acceptedProtocol;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return this.extensions;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        sendError(HttpServletResponse.SC_FORBIDDEN,message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        this.acceptedProtocol = protocol;
    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        this.extensions = extensions;
    }
}
