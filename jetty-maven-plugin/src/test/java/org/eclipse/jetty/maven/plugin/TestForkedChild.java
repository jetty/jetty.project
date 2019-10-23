//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test the JettyForkedChild class, which
 * is the main that is executed by jetty:run/start in mode FORKED.
 *
 */
public class TestForkedChild
{
    File testDir;
    File baseDir;
    File tmpDir;
    File tokenFile;
    File forkWebXml;
    File webappPropsFile;
    int stopPort;
    String stopKey = "FERMATI";
    String jettyPortString;
    int jettyPort;
    String token;
    JettyForkedChild child;
    Thread starter;
    JettyRunner runner = new JettyRunner();
    
    public class JettyRunner implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                List<String> cmd = new ArrayList<String>();
                cmd.add("--stop-port");
                cmd.add(String.valueOf(stopPort));
                cmd.add("--stop-key");
                cmd.add(stopKey);
                cmd.add("--webprops");
                cmd.add(webappPropsFile.getAbsolutePath());
                cmd.add("--token");
                cmd.add(tokenFile.getAbsolutePath());

                JettyWebAppContext webapp = new JettyWebAppContext();
                webapp.setContextPath("/foo");
                webapp.setTempDirectory(tmpDir);
                WebAppPropertyConverter.toProperties(webapp, webappPropsFile, null);
                child = new JettyForkedChild(cmd.toArray(new String[cmd.size()]));
                child.jetty.setExitVm(false); //ensure jetty doesn't stop vm for testing
                child.start();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
    
    @BeforeEach
    public void setUp()
    {
        baseDir = MavenTestingUtils.getTestResourceDir("root");
        testDir = MavenTestingUtils.getTargetTestingDir("forkedChild");
        if (testDir.exists())
            IO.delete(testDir);
        testDir.mkdirs();
        tmpDir = new File(testDir, "tmp");
        forkWebXml = new File(testDir, "fork-web.xml");
        webappPropsFile = new File(testDir, "webapp.props");
        
        stopPort = Integer.valueOf(System.getProperty("stop.port"));
        jettyPortString = System.getProperty("jetty.port");
        jettyPort = Integer.valueOf(jettyPortString);
        
        Random random = new Random();
        token = Long.toString(random.nextLong() ^ System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
        tokenFile = testDir.toPath().resolve(token + ".txt").toFile();
    }
    
    @AfterEach
    public void tearDown() throws Exception
    {
        String command = "forcestop";

        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), stopPort);)
        {
            OutputStream out = s.getOutputStream();
            out.write((stopKey + "\r\n" + command + "\r\n").getBytes());
            out.flush();

            s.setSoTimeout(1000);
            s.getInputStream();

            LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
            String response;
            boolean stopped = false;
            while (!stopped && ((response = lin.readLine()) != null))
            {
                if ("Stopped".equals(response))
                {
                    stopped = true;
                }
            }
        }
    }

    @Test
    public void test() throws Exception
    {      
        starter = new Thread(runner, "JettyForkedChild");
        starter.start();

        //wait for the token file to be created
        int attempts = 20;
        while (!tokenFile.exists() && attempts > 0)
        {
            Thread.currentThread().sleep(500);
            --attempts;
        }
        assertThat(attempts, Matchers.greaterThan(0));

        URL url = new URL("http://localhost:" + jettyPortString + "/foo/");
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection)url.openConnection();
            connection.connect();
            assertThat(connection.getResponseCode(), Matchers.is(200));
        }
        finally
        {
            if (connection != null)
                connection.disconnect();
        }
    }
}
