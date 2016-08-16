package org.eclipse.jetty.embedded;

import java.io.InputStream;
import java.net.Socket;

public class SlowGet
{
    public static void main(String... args) throws Exception
    {
        try(Socket socket = new Socket("localhost",8080))
        {
            socket.getOutputStream().write("GET /data.txt HTTP/1.0\r\n\r\n".getBytes());
            socket.getOutputStream().flush();
            
            InputStream in = socket.getInputStream();
            byte[] headers = new byte[1024];
            int len = in.read(headers);
            
            System.err.println("read="+len);
            
            int b=0;
            while (b>=0)
            {
                b = in.read();
                if ((++len % 1024)==0)
                    System.err.println("read="+(++len));
            }   
        }
    }
}
