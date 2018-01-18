//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.autobahn.client;

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
    private static final int MBYTE = 1024 * 1024;

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

    public static void main(String[] args)
    {
        String hostname = "localhost";
        int port = 9001;

        if (args.length > 0)
        {
            hostname = args[0];
        }

        if (args.length > 1)
        {
            port = Integer.parseInt(args[1]);
        }

        int caseNumbers[] = null;

        // Optional case numbers
        // NOTE: these are url query parameter case numbers (whole integers, eg "6"), not the case ids (eg "7.3.1")
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

            client.updateStatus("Running test suite...");
            client.updateStatus("Using Fuzzing Server: %s:%d", hostname, port);
            client.updateStatus("User Agent: %s", userAgent);

            if (caseNumbers == null)
            {
                int caseCount = client.getCaseCount();
                client.updateStatus("Will run all %d cases ...", caseCount);
                for (int caseNum = 1; caseNum <= caseCount; caseNum++)
                {
                    client.updateStatus("Running case %d (of %d) ...", caseNum, caseCount);
                    client.runCaseByNumber(caseNum);
                }
            }
            else
            {
                client.updateStatus("Will run %d cases ...", caseNumbers.length);
                for (int caseNum : caseNumbers)
                {
                    client.runCaseByNumber(caseNum);
                }
            }
            client.updateStatus("All test cases executed.");
            client.updateReports();
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
        finally
        {
            if (client != null)
            {
                client.shutdown();
            }
        }
        System.exit(0);
    }

    private Logger log;
    private URI baseWebsocketUri;
    private WebSocketCoreClient client;
    private String hostname;
    private int port;
    private String userAgent;

    public AutobahnWebSocketClient(String hostname, int port, String userAgent) throws Exception
    {
        this.log = Log.getLogger(this.getClass());
        this.hostname = hostname;
        this.port = port;
        this.userAgent = userAgent;
        this.baseWebsocketUri = new URI("ws://" + hostname + ":" + port);
        this.client = new WebSocketCoreClient();
        this.client.getPolicy().setMaxBinaryMessageSize(20 * MBYTE);
        this.client.getPolicy().setMaxTextMessageSize(20 * MBYTE);
        // TODO: this should be enabled by default
        // this.client.getExtensionFactory().register("permessage-deflate",PerMessageDeflateExtension.class);
        this.client.start();
    }

    public int getCaseCount() throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        URI wsUri = baseWebsocketUri.resolve("/getCaseCount");
        GetCaseCountHandler onCaseCount = new GetCaseCountHandler();
        Future<FrameHandler.Channel> response = client.connect(onCaseCount, wsUri);

        if (waitForUpgrade(wsUri, response))
        {
            onCaseCount.awaitMessage();
            if (onCaseCount.hasCaseCount())
            {
                return onCaseCount.getCaseCount();
            }
        }
        throw new IllegalStateException("Unable to get Case Count");
    }

    public void runCaseByNumber(int caseNumber) throws IOException, InterruptedException
    {
        URI wsUri = baseWebsocketUri.resolve("/runCase?case=" + caseNumber + "&agent=" + UrlEncoded.encodeString(userAgent));
        log.debug("next uri - {}", wsUri);
        EchoHandler onEchoMessage = new EchoHandler(caseNumber);

        Future<FrameHandler.Channel> response = client.connect(onEchoMessage, wsUri);

        if (waitForUpgrade(wsUri, response))
        {
            onEchoMessage.awaitClose();
        }
    }

    public void shutdown()
    {
        try
        {
            this.client.stop();
        }
        catch (Exception e)
        {
            log.warn("Unable to stop WebSocketClient", e);
        }
    }

    public void updateReports() throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        URI wsUri = baseWebsocketUri.resolve("/updateReports?agent=" + UrlEncoded.encodeString(userAgent));
        UpdateReportsHandler onUpdateReports = new UpdateReportsHandler();
        Future<FrameHandler.Channel> response = client.connect(onUpdateReports, wsUri);
        response.get(5, TimeUnit.SECONDS);
        onUpdateReports.awaitClose();
    }

    public void updateStatus(String format, Object... args)
    {
        log.info(String.format(format, args));
    }

    private boolean waitForUpgrade(URI wsUri, Future<FrameHandler.Channel> response) throws InterruptedException
    {
        try
        {
            response.get(1, TimeUnit.SECONDS);
            return true;
        }
        catch (ExecutionException e)
        {
            log.warn("Unable to connect to: " + wsUri, e);
            return false;
        }
        catch (TimeoutException e)
        {
            log.warn("Unable to connect to: " + wsUri, e);
            return false;
        }
    }
}
