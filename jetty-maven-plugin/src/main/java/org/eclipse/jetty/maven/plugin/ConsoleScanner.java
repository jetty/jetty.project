//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;

/**
 * ConsoleScanner
 *
 * Read input from stdin
 */
public class ConsoleScanner extends Thread
{
    private final AbstractJettyMojo mojo;

    public ConsoleScanner(AbstractJettyMojo mojo)
    {
        this.mojo = mojo;
        setName("Console scanner");
        setDaemon(true);
    }

    @Override
    public void run()
    {
        try
        {
            while (true)
            {
                checkSystemInput();
                getSomeSleep();
            }
        }
        catch (IOException e)
        {
            mojo.getLog().warn(e);
        }
    }

    private void getSomeSleep()
    {
        try
        {
            Thread.sleep(500);
        }
        catch (InterruptedException e)
        {
            mojo.getLog().debug(e);
        }
    }

    private void checkSystemInput() throws IOException
    {
        while (System.in.available() > 0)
        {
            int inputByte = System.in.read();
            if (inputByte >= 0)
            {
                char c = (char)inputByte;
                if (c == '\n')
                {
                    restartWebApp();
                }
            }
        }
    }

    /**
     * Skip buffered bytes of system console.
     */
    private void clearInputBuffer()
    {
        try
        {
            while (System.in.available() > 0)
            {
                // System.in.skip doesn't work properly. I don't know why
                long available = System.in.available();
                for (int i = 0; i < available; i++)
                {
                    if (System.in.read() == -1)
                    {
                        break;
                    }
                }
            }
        }
        catch (IOException e)
        {
            mojo.getLog().warn("Error discarding console input buffer", e);
        }
    }

    private void restartWebApp()
    {
        try
        {
            mojo.restartWebApp(false);
            // Clear input buffer to discard anything entered on the console
            // while the application was being restarted.
            clearInputBuffer();
        }
        catch (Exception e)
        {
            mojo.getLog().error(
                "Error reconfiguring/restarting webapp after a new line on the console",
                e);
        }
    }
}
