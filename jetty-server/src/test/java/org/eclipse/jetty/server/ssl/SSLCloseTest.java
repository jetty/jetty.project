//========================================================================
//Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

// JettyTest.java --
//
// Junit test that shows the Jetty SSL bug.
//

package org.eclipse.jetty.server.ssl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * HttpServer Tester.
 */
public class SSLCloseTest extends TestCase
{
    private static AsyncEndPoint __endp;
    private static class CredulousTM implements TrustManager, X509TrustManager
    {
        public X509Certificate[] getAcceptedIssuers()
        {
            return new X509Certificate[]{};
        }

        public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException
        {
        }

        public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException
        {
        }
    }

    private static final TrustManager[] s_dummyTrustManagers=new TrustManager[]  { new CredulousTM() };

    // ~ Methods
    // ----------------------------------------------------------------

    /**
     * Feed the server the entire request at once.
     *
     * @throws Exception
     */
    public void testClose() throws Exception
    {
        Server server=new Server();
        SslSelectChannelConnector connector=new SslSelectChannelConnector();

        String keystore = System.getProperty("user.dir")+File.separator+"src"+File.separator+"test"+File.separator+"resources"+File.separator+"keystore";

        connector.setPort(0);
        connector.getSslContextFactory().setKeyStorePath(keystore);
        connector.getSslContextFactory().setKeyStorePassword("storepwd");
        connector.getSslContextFactory().setKeyManagerPassword("keypwd");

        server.setConnectors(new Connector[]
        { connector });
        server.setHandler(new WriteHandler());

        server.start();


        SSLContext ctx=SSLContext.getInstance("SSLv3");
        ctx.init(null,s_dummyTrustManagers,new java.security.SecureRandom());

        int port=connector.getLocalPort();

        // System.err.println("write:"+i);
        Socket socket=ctx.getSocketFactory().createSocket("localhost",port);
        OutputStream os=socket.getOutputStream();

        os.write("GET /test HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n".getBytes());
        os.flush();

        BufferedReader in =new BufferedReader(new InputStreamReader(socket.getInputStream()));

        String line;
        while ((line=in.readLine())!=null)
        {
            System.err.println(line);
            if (line.trim().length()==0)
                break;
        }

        Thread.sleep(2000);
        System.err.println(__endp);

        while ((line=in.readLine())!=null)
            System.err.println(line);

    }


    private static class WriteHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setHeader("test","value");
                __endp=(AsyncEndPoint)baseRequest.getConnection().getEndPoint();

                OutputStream out=response.getOutputStream();

                String data = "Now is the time for all good men to come to the aid of the party.\n";
                data+="How now brown cow.\n";
                data+="The quick brown fox jumped over the lazy dog.\n";
                // data=data+data+data+data+data+data+data+data+data+data+data+data+data;
                // data=data+data+data+data+data+data+data+data+data+data+data+data+data;
                data=data+data+data+data;
                byte[] bytes=data.getBytes("UTF-8");

                for (int i=0;i<2;i++)
                {
                    System.err.println("Write "+i+" "+bytes.length);
                    out.write(bytes);
                }
            }
            catch(RuntimeException e)
            {
                e.printStackTrace();
                throw e;
            }
            catch(IOException e)
            {
                e.printStackTrace();
                throw e;
            }
            catch(Error e)
            {
                e.printStackTrace();
                throw e;
            }
            catch(Throwable e)
            {
                e.printStackTrace();
                throw new ServletException(e);
            }
        }

    }

}
