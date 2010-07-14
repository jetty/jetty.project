package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.Socket;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.IO;
import org.junit.AfterClass;


public class HttpServerTestFixture
{    // Useful constants
    protected static final boolean stress = Boolean.getBoolean("STRESS");
    protected static final long PAUSE=10L;
    protected static final int LOOPS=stress?250:25;
    protected static final String HOST="localhost";
    
    protected static Server _server;
    protected static Connector _connector;

    protected Socket newSocket(String host,int port) throws Exception
    {
        return new Socket(host,port);
    }
    
    protected static void startServer(Connector connector) throws Exception
    {
        _server = new Server();
        _connector = connector;
        _server.addConnector(_connector);
        _server.setHandler(new HandlerWrapper());
        _server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    protected void configureServer(Handler handler) throws Exception
    {
        HandlerWrapper current = (HandlerWrapper)_server.getHandler();
        current.stop();
        current.setHandler(handler);
        current.start();
    }
    

    protected static class EchoHandler extends AbstractHandler
    {
        boolean musthavecontent=true;
        
        public EchoHandler()
        {}
        
        public EchoHandler(boolean content)
        {
            musthavecontent=false;
        }
        
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            if (request.getContentType()!=null)
                response.setContentType(request.getContentType());
            if (request.getParameter("charset")!=null)
                response.setCharacterEncoding(request.getParameter("charset"));
            else if (request.getCharacterEncoding()!=null)
                response.setCharacterEncoding(request.getCharacterEncoding());

            PrintWriter writer=response.getWriter();

            int count=0;
            BufferedReader reader=request.getReader();
            if (request.getContentLength()!=0)
            {
                String line;
                
                while ((line=reader.readLine())!=null)
                {
                    writer.print(line);
                    writer.print("\n");
                    count+=line.length();
                }
            }
            
            if (count==0)
            {
                if (musthavecontent)
                    throw new IllegalStateException("no input recieved");

                writer.println("No content");
            }

            // just to be difficult
            reader.close();
            writer.close();

            if (reader.read()>=0)
                throw new IllegalStateException("Not closed");
        }
    }

    protected static class HelloWorldHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().print("Hello world\r\n");
        }
    }

    protected static class DataHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            InputStream in = request.getInputStream();
            String input=IO.toString(in);

            String tmp = request.getParameter("writes");
            int writes=Integer.parseInt(tmp==null?"10":tmp);
            tmp = request.getParameter("block");
            int block=Integer.parseInt(tmp==null?"10":tmp);
            String encoding=request.getParameter("encoding");
            String chars=request.getParameter("chars");

            String chunk = (input+"\u0a870123456789A\u0a87CDEFGHIJKLMNOPQRSTUVWXYZ\u0250bcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
                .substring(0,block);
            response.setContentType("text/plain");
            if (encoding==null)
            {
                byte[] bytes=chunk.getBytes("ISO-8859-1");
                OutputStream out=response.getOutputStream();
                for (int i=0;i<writes;i++)
                    out.write(bytes);
            }
            else if ("true".equals(chars))
            {
                response.setCharacterEncoding(encoding);
                Writer out=response.getWriter();
                char[] c=chunk.toCharArray();
                for (int i=0;i<writes;i++)
                    out.write(c);
            }
            else
            {
                response.setCharacterEncoding(encoding);
                Writer out=response.getWriter();
                for (int i=0;i<writes;i++)
                    out.write(chunk);
            }

        }
    }
}
