package test;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet( "/*" )
public class Greeter
    extends HttpServlet
{

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
        throws ServletException, IOException
    {
        resp.getWriter().print( "Hello" );
    }
}
