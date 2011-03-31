package org.eclipse.jetty.nested;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.bio.StreamEndPoint;

public class NestedEndPoint extends StreamEndPoint
{
    public NestedEndPoint(HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        super(request.getInputStream(),response.getOutputStream());
    }

    public ServletInputStream getServletInputStream()
    {
        return (ServletInputStream)getInputStream();
    }

}
