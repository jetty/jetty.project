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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.autobahn;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *     # Change to websocket-jakarta-tests directory first.
 *     $ cd jetty-websocket/websocket-jakarta-tests/
 *     $ wstest --mode=fuzzingserver --spec=fuzzingserver.json
 *
 *     # Report output is configured (in the fuzzingserver.json) at location:
 *     $ ls target/reports/clients/
 * </pre>
 */
public class JakartaAutobahnClient
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

        JakartaAutobahnClient client = null;
        try
        {
            String userAgent = "JettyWebsocketClient/" + Jetty.VERSION;
            client = new JakartaAutobahnClient(hostname, port, userAgent);

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
                LOG.info("Will run {} cases ...", caseNumbers.length);
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
                client.stop();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(JakartaAutobahnClient.class);

    private URI baseWebsocketUri;
    private JakartaWebSocketClientContainer clientContainer;
    private String userAgent;

    public JakartaAutobahnClient(String hostname, int port, String userAgent) throws Exception
    {
        this.userAgent = userAgent;
        this.baseWebsocketUri = new URI("ws://" + hostname + ":" + port);
        this.clientContainer = new JakartaWebSocketClientContainer();
        clientContainer.start();
    }

    public void stop()
    {
        LifeCycle.stop(clientContainer);
    }

    public int getCaseCount()
    {
        URI wsUri = baseWebsocketUri.resolve("/getCaseCount");
        EventSocket onCaseCount = new EventSocket();

        try
        {
            clientContainer.connectToServer(onCaseCount, wsUri);
            String msg = onCaseCount.textMessages.poll(10, TimeUnit.SECONDS);
            onCaseCount.session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, null));
            assertTrue(onCaseCount.closeLatch.await(2, TimeUnit.SECONDS));
            assertNotNull(msg);
            return Integer.decode(msg);
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Unable to get Case Count", t);
        }
    }

    public void runCaseByNumber(int caseNumber) throws Exception
    {
        URI wsUri = baseWebsocketUri.resolve("/runCase?case=" + caseNumber + "&agent=" + UrlEncoded.encodeString(userAgent));
        LOG.info("test uri: {}", wsUri);

        JakartaAutobahnSocket echoHandler = new JakartaAutobahnSocket();
        clientContainer.connectToServer(echoHandler, wsUri);

        // Wait up to 5 min as some of the tests can take a while
        if (!echoHandler.closeLatch.await(5, TimeUnit.MINUTES))
        {
            LOG.warn("could not close {}, closing session", echoHandler);
            echoHandler.session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, null));
        }
    }

    public void updateReports() throws Exception
    {
        URI wsUri = baseWebsocketUri.resolve("/updateReports?agent=" + UrlEncoded.encodeString(userAgent));
        EventSocket onUpdateReports = new EventSocket();
        clientContainer.connectToServer(onUpdateReports, wsUri);
        assertTrue(onUpdateReports.closeLatch.await(15, TimeUnit.SECONDS));
        LOG.info("Reports updated.");
        LOG.info("Test suite finished!");
    }
}
