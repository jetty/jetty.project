//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.server;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.StringTokenizer;

import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.Before;
import org.junit.Test;

/**
 * HttpServer Tester.
 */
public abstract class ConnectorCloseTestBase extends HttpServerTestFixture
{
    private static String __content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "+
        "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "+
        "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. "+
        "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "+
        "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "+
        "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. "+
        "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "+
        "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "+
        "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "+
        "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "+
        "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "+
        "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";
    private static int __length = __content.length();
    
    private StringBuffer _response;
    private boolean _continue;

    /* ------------------------------------------------------------ */
    @Test
    public void testCloseBetweenRequests() throws Exception
    {
        int total = 0;

        _continue = true;
        _response = new StringBuffer();
        
        configureServer(new HelloWorldHandler());

        Socket client = newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os = client.getOutputStream();

            ResponseReader reader = new ResponseReader(client);
            Thread runner = new Thread(reader);
            runner.start();

            for (int pipeline = 1; pipeline < 32; pipeline++)
            {
                if (pipeline == 16)
                    _connector.close();

                total += pipeline;

                String request = "";
                for (int i = 0; i < pipeline; i++)
                {
                    request +=
                        "GET /data?writes=1&block=16&id="+i+" HTTP/1.1\r\n"+
                        "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                        "user-agent: testharness/1.0 (blah foo/bar)\r\n"+
                        "accept-encoding: nothing\r\n"+
                        "cookie: aaa=1234567890\r\n"+
                        "\r\n";
                }
                os.write(request.getBytes());
                os.flush();

                Thread.sleep(50);
            }
            
            _continue = false;
            runner.join();
        }
        finally
        {
            client.close();

            int count = 0;
            StringTokenizer lines = new StringTokenizer(_response.toString(),"\r\n");
            while(lines.hasMoreTokens())
            {
                String line = lines.nextToken();
                if (line.equals("HTTP/1.1 200 OK"))
                {
                    ++count;
                }
            }
            
            assertEquals(total, count);
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testCloseBetweenChunks() throws Exception
    {
        _continue = true;
        _response = new StringBuffer();

        configureServer(new EchoHandler());

        Socket client = newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os = client.getOutputStream();

            ResponseReader reader = new ResponseReader(client);
            Thread runner = new Thread(reader);
            runner.start();

            String content = "abcdefghij";
            byte[] bytes = __content.getBytes("utf-8");
            
            os.write((
                "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "content-type: text/plain; charset=utf-8\r\n"+
                "content-length: "+bytes.length+"\r\n"+
                "\r\n"
            ).getBytes("iso-8859-1"));

            int len = bytes.length;
            int offset = 0;
            int stop = len / 2;
            while (offset < stop)
            {
                os.write(bytes, offset, 64);
                offset += 64;
            }
            
            _connector.close();

            while (offset < len)
            {
                os.write(bytes, offset, len-offset <=64 ? len-offset : 64);
                offset += 64;
            }
            os.flush();

            Thread.sleep(50);

            _continue = false;
            runner.join();
            
            String in = _response.toString();
            assertTrue(in.indexOf(__content.substring(__length-64))>0);
        }
        finally
        {
            client.close();
        }
    }


    /* ------------------------------------------------------------ */
    public class ResponseReader implements Runnable
    {
        private BufferedReader _reader;
        
        /* ------------------------------------------------------------ */
        public ResponseReader(Socket client) throws IOException
        {
            _reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @see java.lang.Runnable#run()
         */
        public void run()
        {
            int count = 0;
            char[] buffer = new char[256];
            
            try
            {
                while (_continue)
                {
                    if (_reader.ready())
                    {
                        count = _reader.read(buffer);
                        _response.append(buffer, 0, count);
                    }
                    else
                    {
                        count = 0;
                        Thread.sleep(10);
                    }
                    
                }
            }
            catch (IOException ex) { }
            catch (InterruptedException ex) { }
            finally
            {
                try
                {
                    _reader.close();
                }
                catch (IOException e) { }
            }
        }
    }
}
