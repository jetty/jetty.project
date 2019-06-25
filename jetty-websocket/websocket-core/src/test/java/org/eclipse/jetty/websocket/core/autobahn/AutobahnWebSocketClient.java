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

package org.eclipse.jetty.websocket.core.autobahn;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket Client for use with <a href="https://github.com/crossbario/autobahn-testsuite">autobahn websocket testsuite</a> (wstest).
 * <p>
 * Installing Autobahn:
 * </p>
 * <pre>
 *    # For Debian / Ubuntu
 *    $ sudo apt-get install python python-dev python-twisted
 *    $ sudo apt-get install python-pip
 *    $ sudo pip install autobahntestsuite
 *
 *    # For Fedora / Redhat
 *    $ sudo yum install python python-dev python-pip twisted
 *    $ sudo yum install libffi-devel
 *    $ sudo pip install autobahntestsuite
 * </pre>
 * <p>
 * Upgrading an existing installation of autobahntestsuite
 * </p>
 * <pre>
 *     $ sudo pip install -U autobahntestsuite
 * </pre>
 * <p>
 * Running Autobahn Fuzzing Server (which you run this client implementation against):
 * </p>
 * <pre>
 *     # Change to websocket-core
 *     $ cd jetty-websocket/websocket-core
 *     $ wstest --mode=fuzzingserver --spec=fuzzingserver.json
 *
 *     # Report output is configured (in the fuzzingserver.json) at location:
 *     $ ls target/reports/clients/
 * </pre>
 */
public class AutobahnWebSocketClient
{
    public static void main(String[] args)
    {
        String hostname = "localhost";
        int port = 9001;

        if (args.length > 0)
            hostname = args[0];
        if (args.length > 1)
            port = Integer.parseInt(args[1]);

        // Optional case numbers
        // NOTE: these are url query parameter case numbers (whole integers, eg "6"), not the case ids (eg "7.3.1")
        int[] caseNumbers = null;
        if (args.length > 2)
        {
            int offset = 2;
            caseNumbers = new int[args.length - offset];
            for (int i = offset; i < args.length; i++)
            {
                caseNumbers[i - offset] = Integer.parseInt(args[i]);
            }
        }

        AutobahnWebSocketClient client = null;
        try
        {
            String userAgent = "JettyWebsocketClient/" + getJettyVersion();
            client = new AutobahnWebSocketClient(hostname, port, userAgent);

            LOG.info("Running test suite...");
            LOG.info("Using Fuzzing Server: {}:{}", hostname, port);
            LOG.info("User Agent: {}", userAgent);

            if (caseNumbers == null)
            {
                int caseCount = client.getCaseCount();
                LOG.info("Will run all {} cases ...", caseCount);
                for (int caseNum = 1; caseNum <= caseCount; caseNum++)
                {
                    LOG.info("Running case {} (of {}) ...", caseNum, caseCount);
                    client.runCaseByNumber(caseNum);
                }
            }
            else
            {
                LOG.info("Will run %d cases ...", caseNumbers.length);
                for (int caseNum : caseNumbers)
                {
                    client.runCaseByNumber(caseNum);
                }
            }
            LOG.info("All test cases executed.");
            client.updateReports();
        }
        catch (Throwable t)
        {
            LOG.warn("Test Failed", t);
        }
        finally
        {
            if (client != null)
                client.shutdown();
        }
    }

    private static final Logger LOG = Log.getLogger(AutobahnWebSocketClient.class);
    private URI baseWebsocketUri;
    private WebSocketCoreClient client;
    private String userAgent;

    public AutobahnWebSocketClient(String hostname, int port, String userAgent) throws Exception
    {
        this.userAgent = userAgent;
        this.baseWebsocketUri = new URI("ws://" + hostname + ":" + port);
        this.client = new WebSocketCoreClient();
        this.client.start();
    }

    public int getCaseCount() throws IOException, InterruptedException
    {
        URI wsUri = baseWebsocketUri.resolve("/getCaseCount");
        TestMessageHandler onCaseCount = new TestMessageHandler();
        Future<FrameHandler.CoreSession> response = client.connect(onCaseCount, wsUri);

        if (waitForUpgrade(wsUri, response))
        {
            String msg = onCaseCount.textMessages.poll(2, TimeUnit.SECONDS);
            onCaseCount.getCoreSession().abort(); // Don't expect normal close
            assertTrue(onCaseCount.closeLatch.await(2, TimeUnit.SECONDS));
            assertNotNull(msg);
            return Integer.decode(msg);
        }
        throw new IllegalStateException("Unable to get Case Count");
    }

    public void runCaseByNumber(int caseNumber) throws IOException, InterruptedException
    {
        URI wsUri = baseWebsocketUri.resolve("/runCase?case=" + caseNumber + "&agent=" + UrlEncoded.encodeString(userAgent));
        LOG.info("test uri: {}", wsUri);

        AutobahnFrameHandler echoHandler = new AutobahnFrameHandler();
        Future<FrameHandler.CoreSession> response = client.connect(echoHandler, wsUri);
        if (waitForUpgrade(wsUri, response))
        {
            if (!echoHandler.closeLatch.await(5, TimeUnit.MINUTES))
            {
                LOG.warn("could not close {}, aborting session", echoHandler);
                echoHandler.coreSession.abort();
            }
        }
    }

    public void shutdown()
    {
        try
        {
            client.stop();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to stop WebSocketClient", e);
        }
    }

    public void updateReports() throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        URI wsUri = baseWebsocketUri.resolve("/updateReports?agent=" + UrlEncoded.encodeString(userAgent));
        TestMessageHandler onUpdateReports = new TestMessageHandler();
        Future<FrameHandler.CoreSession> response = client.connect(onUpdateReports, wsUri);
        response.get(5, TimeUnit.SECONDS);
        assertTrue(onUpdateReports.closeLatch.await(15, TimeUnit.SECONDS));
        LOG.info("Reports updated.");
        LOG.info("Test suite finished!");
    }

    private boolean waitForUpgrade(URI wsUri, Future<FrameHandler.CoreSession> response) throws InterruptedException
    {
        try
        {
            response.get(10, TimeUnit.SECONDS);
            return true;
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to connect to: " + wsUri, t);
            return false;
        }
    }

    private static String getJettyVersion() throws IOException
    {
        String resource = "META-INF/maven/org.eclipse.jetty.websocket/websocket-core/pom.properties";
        URL url = Thread.currentThread().getContextClassLoader().getResource(resource);
        if (url == null)
        {
            if (AutobahnWebSocketClient.class.getPackage() != null)
            {
                Package pkg = AutobahnWebSocketClient.class.getPackage();
                if (pkg.getImplementationVersion() != null)
                {
                    return pkg.getImplementationVersion();
                }
            }
            return "GitMaster";
        }

        try (InputStream in = url.openStream())
        {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version");
        }
    }
}
