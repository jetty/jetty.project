//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.awaitility.Awaitility;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test the JettyForkedChild class, which
 * is the main that is executed by jetty:run/start in mode FORKED.
 */
@ExtendWith(WorkDirExtension.class)
public class TestForkedChild
{
    File testDir;
    File baseDir;
    Path tmpDir;
    File tokenFile;
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
                List<String> cmd = new ArrayList<>();
                cmd.add("--stop-port");
                cmd.add(String.valueOf(stopPort));
                cmd.add("--stop-key");
                cmd.add(stopKey);
                cmd.add("--webprops");
                cmd.add(webappPropsFile.getAbsolutePath());
                cmd.add("--token");
                cmd.add(tokenFile.getAbsolutePath());

                MavenWebAppContext webapp = new MavenWebAppContext();
                webapp.setContextPath("/foo");
                webapp.setTempDirectory(tmpDir.toFile());
                webapp.setBaseResourceAsPath(baseDir.toPath());
                WebAppPropertyConverter.toProperties(webapp, webappPropsFile, null);
                child = new JettyForkedChild(cmd.toArray(new String[0]));
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
    public void setUp(WorkDir workDir)
    {
        tmpDir = workDir.getEmptyPathDir();
        baseDir = MavenTestingUtils.getTestResourceDir("root");
        testDir = MavenTestingUtils.getTargetTestingDir("forkedChild");
        FS.ensureEmpty(testDir);
        webappPropsFile = new File(tmpDir.toFile(), "webapp.props");

        String stopPortString = System.getProperty("stop.port");
        assertNotNull(stopPortString, "stop.port System property");
        stopPort = Integer.parseInt(stopPortString);
        jettyPortString = System.getProperty("jetty.port");
        assertNotNull(jettyPortString, "jetty.port System property");
        jettyPort = Integer.parseInt(jettyPortString);

        Random random = new Random();
        token = Long.toString(random.nextLong() ^ System.currentTimeMillis(), 36).toUpperCase(Locale.ENGLISH);
        tokenFile = tmpDir.resolve(token + ".txt").toFile();
    }
    
    @AfterEach
    public void tearDown() throws Exception
    {
        String command = "forcestop";

        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), stopPort))
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
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(tokenFile::exists);

        URL url = new URL("http://localhost:" + jettyPortString + "/foo/");
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection)url.openConnection();
            connection.connect();
            assertThat(connection.getResponseCode(), Matchers.is(200));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IO.copy(connection.getInputStream(), baos);
            assertThat(baos.toString(), Matchers.containsString("ROOT"));
        }
        finally
        {
            if (connection != null)
                connection.disconnect();
        }
    }
}
