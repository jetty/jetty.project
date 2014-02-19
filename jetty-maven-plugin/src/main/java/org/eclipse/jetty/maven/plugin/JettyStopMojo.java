//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * JettyStopMojo - stops a running instance of jetty.
 * The ff are required:
 * -DstopKey=someKey
 * -DstopPort=somePort
 * 
 * @goal stop
 * @description Stops jetty that is configured with &lt;stopKey&gt; and &lt;stopPort&gt;.
 */

public class JettyStopMojo extends AbstractMojo
{
    
    /**
     * Port to listen to stop jetty on sending stop command
     * @parameter
     * @required
     */
    protected int stopPort;
    
    /**
     * Key to provide when stopping jetty on executing java -DSTOP.KEY=&lt;stopKey&gt; 
     * -DSTOP.PORT=&lt;stopPort&gt; -jar start.jar --stop
     * @parameter
     * @required
     */
    protected String stopKey;
    
    /**
     * Max time in seconds that the plugin will wait for confirmation that jetty has stopped.
     * @parameter
     */
    protected int stopWait;
    

    public void execute() throws MojoExecutionException, MojoFailureException 
    {
        if (stopPort <= 0)
            throw new MojoExecutionException("Please specify a valid port"); 
        if (stopKey == null)
            throw new MojoExecutionException("Please specify a valid stopKey");  

        try
        {        
            Socket s=new Socket(InetAddress.getByName("127.0.0.1"),stopPort);
            s.setSoLinger(false, 0);

            OutputStream out=s.getOutputStream();
            out.write((stopKey+"\r\nstop\r\n").getBytes());
            out.flush();

            if (stopWait > 0)
            {      
                s.setSoTimeout(stopWait * 1000);
                s.getInputStream();

                getLog().info("Waiting "+stopWait+" seconds for jetty to stop");
                LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                String response;
                boolean stopped = false;
                while (!stopped && ((response = lin.readLine()) != null))
                {
                    if ("Stopped".equals(response))
                    {
                        stopped = true;
                        getLog().info("Server reports itself as stopped");
                    }                
                }
            }
            s.close();
        }
        catch (ConnectException e)
        {
            getLog().info("Jetty not running!");
        }
        catch (Exception e)
        {
            getLog().error(e);
        }
    }

    public int getStopPort() {
        return stopPort;
    }

    public void setStopPort(int stopPort) {
        this.stopPort = stopPort;
    }

    public String getStopKey() {
        return stopKey;
    }

    public void setStopKey(String stopKey) {
        this.stopKey = stopKey;
    }
}
