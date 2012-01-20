package org.eclipse.jetty.servlets.gzip;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;

/**
 * Test servlet for testing against unusual minGzip configurable.
 */
@SuppressWarnings("serial")
public class TestMinGzipSizeServlet extends TestDirContentServlet
{
    private MimeTypes mimeTypes;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        mimeTypes = new MimeTypes();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String fileName = request.getServletPath();
        byte[] dataBytes = loadContentFileBytes(fileName);

        response.setContentLength(dataBytes.length);
        if (fileName.endsWith(".js"))
        {
            // intentionally long-form content type to test ";" splitting in code
            response.setContentType("text/javascript; charset=utf-8");
        }
        else
        {
            Buffer buf = mimeTypes.getMimeByExtension(fileName);
            if (buf != null)
            {
                response.setContentType(buf.toString());
            }
        }
        ServletOutputStream out = response.getOutputStream();
        out.write(dataBytes);
    }
}
