//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import org.eclipse.jetty.toolchain.test.IO;


public class ProxyFakeTunnelTest extends ProxyTunnellingTest
{
    ServerSocket _proxySocket;
    Thread _proxyThread;

    protected int proxyPort()
    {
        return _proxySocket.getLocalPort();
    }
    

    protected void startProxy() throws Exception
    {
        _proxySocket = new ServerSocket(0);
        
        _proxyThread = new Thread()
        {
            @Override
            public void run()
            {
                while (!_proxySocket.isClosed())
                {
                    try
                    {
                        Socket socket=_proxySocket.accept();
                        System.err.println("accepted "+socket);
                        new FakeProxy(socket).start();
                    }
                    catch (IOException e)
                    {
                    }
                }
            }
        };
        _proxyThread.setDaemon(true);
        _proxyThread.start();
        
    }

    protected void stopProxy() throws Exception
    {
        _proxySocket.close();
        _proxyThread.interrupt();
    }

    static class FakeProxy extends Thread
    {
        Socket _socket;
        
        public FakeProxy(Socket socket)
        {
            _socket=socket;
        }

        public void run()
        {

            Socket toserver=null;
            final InputStream in;
            final OutputStream out; 
            try
            {
                 in = _socket.getInputStream();
                 out = _socket.getOutputStream();
                
                String address="";
                int state=0;
                
                for (int b=in.read();b>=0;b=in.read())
                {   
                    switch(state)
                    {
                        case 0:
                            if (' '==b)
                                state=1;
                            break;
                            
                        case 1:
                            if (' '==b)
                                state=2;
                            else
                                address+=(char)b;
                            break;
                            
                        case 2:
                            if ('\r'==b)
                                state=3;
                            break;
                            
                        case 3:
                            if ('\n'==b)
                                state=4;
                            else
                                state=2;
                            break;
                            
                        case 4:
                            if ('\r'==b)
                                state=5;
                            else
                                state=2;
                            break;
                            
                        case 5:
                            if ('\n'==b)
                            {
                                state=6;
                                System.err.println("address="+address);
                                String[] parts=address.split(":");
                                try
                                {
                                    toserver = new Socket(parts[0],Integer.parseInt(parts[1]));
                                    out.write((
                                            "HTTP/1.1 200 OK\r\n"+
                                            "Server: fake\r\n"+
                                            // "Content-Length: 0\r\n"+ 
                                            "\r\n"
                                            ).getBytes());
                                }
                                catch(IOException e)
                                {
                                    out.write((
                                            "HTTP/1.1 503 Unavailable\r\n"+
                                            "Server: fake\r\n"+
                                            "Content-Length: 0\r\n"+ 
                                            "\r\n"
                                            ).getBytes());
                                }
                                out.flush();
           
                                if (toserver!=null)
                                {
                                    final InputStream from = toserver.getInputStream();

                                    Thread copy = new Thread()
                                    {
                                        public void run()
                                        {
                                            try
                                            {
                                                IO.copy(from,out);
                                                out.close();
                                            }
                                            catch (IOException e)
                                            {
                                            }
                                            finally
                                            {
                                                try
                                                {
                                                    out.close();
                                                }
                                                catch (IOException e)
                                                {
                                                }
                                            }
                                        }
                                    };
                                    copy.setDaemon(true);
                                    copy.start();
                                }
                                
                            }
                            else
                                state=2;
                            break;

                        case 6:
                            toserver.getOutputStream().write((byte)b);
                            
                    }
                }
                
                
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            finally
            {
                if (toserver!=null)
                {
                    try
                    {
                        toserver.close();
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
        
    }
    
    
}
