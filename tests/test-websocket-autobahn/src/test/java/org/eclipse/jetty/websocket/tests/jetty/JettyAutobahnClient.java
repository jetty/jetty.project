//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.jetty;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.AutobahnClient;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 *     # Change to websocket-jetty-tests directory first.
 *     $ cd jetty-websocket/websocket-jetty-tests/
 *     $ wstest --mode=fuzzingserver --spec=fuzzingserver.json
 *
 *     # Report output is configured (in the fuzzingserver.json) at location:
 *     $ ls target/reports/clients/
 * </pre>
 */
public class JettyAutobahnClient implements AutobahnClient
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

        JettyAutobahnClient client = new JettyAutobahnClient();
        client.runAutobahnClient(hostname, port, caseNumbers);
    }

    private static final Logger LOG = LoggerFactory.getLogger(JettyAutobahnClient.class);
    private URI baseWebsocketUri;
    private WebSocketClient client;
    private String userAgent;

    @Override
    public void runAutobahnClient(String hostname, int port, int[] caseNumbers)
    {
        try
        {
            String userAgent = "JettyWebsocketClient/" + Jetty.VERSION;
            this.userAgent = userAgent;
            this.baseWebsocketUri = new URI("ws://" + hostname + ":" + port);
            this.client = new WebSocketClient();
            this.client.start();

            LOG.info("Running test suite...");
            LOG.info("Using Fuzzing Server: {}:{}", hostname, port);
            LOG.info("User Agent: {}", userAgent);

            if (caseNumbers == null)
            {
                int caseCount = getCaseCount();
                LOG.info("Will run all {} cases ...", caseCount);
                for (int caseNum = 1; caseNum <= caseCount; caseNum++)
                {
                    LOG.info("Running case {} (of {}) ...", caseNum, caseCount);
                    runCaseByNumber(caseNum);
                }
            }
            else
            {
                LOG.info("Will run {} cases ...", caseNumbers.length);
                for (int caseNum : caseNumbers)
                {
                    runCaseByNumber(caseNum);
                }
            }
            LOG.info("All test cases executed.");
            updateReports();
        }
        catch (Throwable t)
        {
            LOG.warn("Test Failed", t);
        }
        finally
        {
            shutdown();
        }
    }

    public int getCaseCount() throws Exception
    {
        URI wsUri = baseWebsocketUri.resolve("/getCaseCount");
        EventSocket onCaseCount = new EventSocket();
        Future<Session> response = upgrade(onCaseCount, wsUri);

        if (waitForUpgrade(wsUri, response))
        {
            String msg = onCaseCount.textMessages.poll(10, TimeUnit.SECONDS);
            onCaseCount.session.close(StatusCode.SHUTDOWN, null);
            Assertions.assertTrue(onCaseCount.closeLatch.await(2, TimeUnit.SECONDS));
            assertNotNull(msg);
            return Integer.decode(msg);
        }
        throw new IllegalStateException("Unable to get Case Count");
    }

    public void runCaseByNumber(int caseNumber) throws Exception
    {
        URI wsUri = baseWebsocketUri.resolve("/runCase?case=" + caseNumber + "&agent=" + UrlEncoded.encodeString(userAgent));
        LOG.info("test uri: {}", wsUri);

        EchoSocket echoHandler = new JettyAutobahnSocket();
        Future<Session> response = upgrade(echoHandler, wsUri);
        if (waitForUpgrade(wsUri, response))
        {
            // Wait up to 5 min as some of the tests can take a while
            if (!echoHandler.closeLatch.await(5, TimeUnit.MINUTES))
            {
                LOG.warn("could not close {}, aborting session", echoHandler);
                echoHandler.session.disconnect();
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

    public Future<Session> upgrade(Object handler, URI uri) throws Exception
    {
        // We manually set the port as we run the server in docker container.
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setHeader(HttpHeader.HOST.asString(), "localhost:9001");
        return client.connect(handler, uri, upgradeRequest);
    }

    public void updateReports() throws Exception
    {
        URI wsUri = baseWebsocketUri.resolve("/updateReports?agent=" + UrlEncoded.encodeString(userAgent));
        EventSocket onUpdateReports = new EventSocket();
        Future<Session> response = upgrade(onUpdateReports, wsUri);
        response.get(5, TimeUnit.SECONDS);
        Assertions.assertTrue(onUpdateReports.closeLatch.await(15, TimeUnit.SECONDS));
        LOG.info("Reports updated.");
        LOG.info("Test suite finished!");
    }

    private boolean waitForUpgrade(URI wsUri, Future<Session> response)
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
}
