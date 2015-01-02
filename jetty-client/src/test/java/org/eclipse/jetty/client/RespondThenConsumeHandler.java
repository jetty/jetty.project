package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;

class RespondThenConsumeHandler extends AbstractHandler
{
    @Override
    public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        baseRequest.setHandled(true);
        response.setContentLength(0);
        response.setStatus(200);
        response.flushBuffer();
        
        InputStream in = request.getInputStream();
        while(in.read()>=0);
    }
    
}