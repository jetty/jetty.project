//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.its.jetty_run_mojo_it;

import java.io.File;
import java.util.*;
import org.eclipse.jetty.client.HttpClient;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.eclipse.jetty.toolchain.perf.PlatformTimer;
import java.util.concurrent.*;

/**
 *
 */
public class TestHelloServlet
{

    private final PlatformTimer timer = PlatformTimer.detect();

    @Test
    public void hello_servlet()
        throws Exception
    {
        int port = Integer.getInteger( "jetty.runPort" );
        System.out.println( "port used:" + port );
        HttpClient httpClient = new HttpClient();
        try
        {
            httpClient.start();

            String response = httpClient.GET( "http://localhost:" + port + "/hello?name=beer" ).getContentAsString();

            System.out.println( "httpResponse hello annotation servlet:" + response );

            Assert.assertEquals( "hello beer", response.trim() );

            response = httpClient.GET( "http://localhost:" + port + "/ping?name=beer" ).getContentAsString();

            System.out.println( "httpResponse ping fragment servlet:" + response );

            Assert.assertEquals( "pong beer", response.trim() );

            new File("pom.xml").setLastModified( new Date( ).getTime() );

            new File("target/classes/touch.txt").setLastModified( new Date( ).getTime() );
            Files.write( Paths.get("target/classes/touch.txt"), "foo".getBytes());

            System.out.println( "touch pom.xml touch.txt");

            //Thread.sleep(7000);
            timer.sleep(TimeUnit.SECONDS.toMicros(7));

            response = httpClient.GET( "http://localhost:" + port + "/hello?name=beer" ).getContentAsString();

            System.out.println( "httpResponse hello annotation servlet:" + response );

            Assert.assertEquals( "hello beer", response.trim() );

            response = httpClient.GET( "http://localhost:" + port + "/ping?name=beer" ).getContentAsString();

            System.out.println( "httpResponse ping fragment servlet:" + response );

            Assert.assertEquals( "pong beer", response.trim() );

        }
        finally
        {
            httpClient.stop();
        }
    }
}
