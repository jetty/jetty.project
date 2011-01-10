// ========================================================================
// Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.start;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/*-------------------------------------------*/
/** Monitor thread.
 * This thread listens on the port specified by the STOP.PORT system parameter
 * (defaults to -1 for not listening) for request authenticated with the key given by the STOP.KEY
 * system parameter (defaults to "eclipse") for admin requests. 
 * <p>
 * If the stop port is set to zero, then a random port is assigned and the port number
 * is printed to stdout.
 * <p>
 * Commands "stop" and * "status" are currently supported.
 *
 */
public class Monitor extends Thread
{
    private Process _process;
    private final int _port;
    private final String _key;

    ServerSocket _socket;
    
    public Monitor(int port,String key)
    {
        try
        {
            if(port<0)
                return;
            setDaemon(true);
	    setName("StopMonitor");
            _socket=new ServerSocket(port,1,InetAddress.getByName("127.0.0.1"));
            if (port==0)
            {
                port=_socket.getLocalPort();
                System.out.println(port);
            }
            
            if (key==null)
            {
                key=Long.toString((long)(Long.MAX_VALUE*Math.random()+this.hashCode()+System.currentTimeMillis()),36);
                System.out.println("STOP.KEY="+key);
            }
        }
        catch(Exception e)
        {
            Config.debug(e);
            System.err.println(e.toString());
        }
        finally
        {
            _port=port;
            _key=key;
        }
        
        if (_socket!=null)
            this.start();
        else
            System.err.println("WARN: Not listening on monitor port: "+_port);
    }
    
    public Process getProcess()
    {
        return _process;
    }
    
    public void setProcess(Process process)
    {
        _process = process;
    }
    
    @Override
    public void run()
    {
        while (true)
        {
            Socket socket=null;
            try{
                socket=_socket.accept();
                
                LineNumberReader lin=
                    new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                String key=lin.readLine();
                if (!_key.equals(key))
                    continue;
                
                String cmd=lin.readLine();
                Config.debug("command=" + cmd);
                if ("stop".equals(cmd))
                {
                    try {socket.close();}catch(Exception e){e.printStackTrace();}
                    try {_socket.close();}catch(Exception e){e.printStackTrace();}
                    if (_process!=null)
                        _process.destroy();
                    System.exit(0);
                }
                else if ("status".equals(cmd))
                {
                    socket.getOutputStream().write("OK\r\n".getBytes());
                    socket.getOutputStream().flush();
                }
            }
            catch(Exception e)
            {
                Config.debug(e);
                System.err.println(e.toString());
            }
            finally
            {
                if (socket!=null)
                {
                    try{socket.close();}catch(Exception e){}
                }
                socket=null;
            }
        }
    }
 
}
