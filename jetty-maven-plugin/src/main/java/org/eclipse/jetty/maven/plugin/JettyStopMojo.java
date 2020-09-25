//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/**
 * This goal stops a running instance of jetty.
 *
 * The <b>stopPort</b> and <b>stopKey</b> parameters can be used to
 * configure which jetty to stop.
 */
@Mojo(name = "stop")
public class JettyStopMojo extends AbstractWebAppMojo
{
    /**
     * Max time in seconds that the plugin will wait for confirmation that jetty has stopped.
     */
    @Parameter
    protected int stopWait;
    
    @Override
    protected void startJettyEmbedded() throws MojoExecutionException
    {
        //Does not start jetty
        return;
    }

    @Override
    protected void startJettyForked() throws MojoExecutionException
    {
        //Does not start jetty
        return;
    }

    @Override
    protected void startJettyHome() throws MojoExecutionException
    {
        //Does not start jetty
        return;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (stopPort <= 0)
            throw new MojoExecutionException("Please specify a valid port");
        if (stopKey == null)
            throw new MojoExecutionException("Please specify a valid stopKey");

        String command = "forcestop";

        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), stopPort);)
        {
            OutputStream out = s.getOutputStream();
            out.write((stopKey + "\r\n" + command + "\r\n").getBytes());
            out.flush();

            if (stopWait > 0)
            {
                s.setSoTimeout(stopWait * 1000);
                s.getInputStream();

                getLog().info("Waiting " + stopWait + " seconds for jetty to stop");
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
}
