//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CustomResourcesMonitorTest
{
    Server _server;
    ServerConnector _connector;
    FileOnDirectoryMonitor _fileOnDirectoryMonitor;
    Path _monitoredPath;
    LowResourceMonitor _lowResourceMonitor;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();

        _server.addBean(new TimerScheduler());

        _connector = new ServerConnector(_server);
        _connector.setPort(0);
        _connector.setIdleTimeout(35000);
        _server.addConnector(_connector);

        _server.setHandler(new DumpHandler());

        _monitoredPath = Files.createTempDirectory("jetty_test");
        _fileOnDirectoryMonitor = new FileOnDirectoryMonitor(_monitoredPath);
        _lowResourceMonitor = new LowResourceMonitor(_server);
        _server.addBean(_lowResourceMonitor);
        _lowResourceMonitor.addLowResourceCheck(_fileOnDirectoryMonitor);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testFileOnDirectoryMonitor() throws Exception
    {
        int monitorPeriod = _lowResourceMonitor.getPeriod();
        int lowResourcesIdleTimeout = _lowResourceMonitor.getLowResourcesIdleTimeout();
        assertThat(lowResourcesIdleTimeout, Matchers.lessThanOrEqualTo(monitorPeriod));

        int maxLowResourcesTime = 5 * monitorPeriod;
        _lowResourceMonitor.setMaxLowResourcesTime(maxLowResourcesTime);
        assertFalse(_fileOnDirectoryMonitor.isLowOnResources());

        try (Socket socket0 = new Socket("localhost", _connector.getLocalPort()))
        {
            Path tmpFile = Files.createTempFile(_monitoredPath, "yup", ".tmp");
            // Write a file
            Files.write(tmpFile, "foobar".getBytes());

            // Wait a couple of monitor periods so that
            // fileOnDirectoryMonitor detects it is in low mode.
            Thread.sleep(2 * monitorPeriod);
            assertTrue(_fileOnDirectoryMonitor.isLowOnResources());

            // We already waited enough for fileOnDirectoryMonitor to close socket0.
            assertEquals(-1, socket0.getInputStream().read());

            // New connections are not affected by the
            // low mode until maxLowResourcesTime elapses.
            try (Socket socket1 = new Socket("localhost", _connector.getLocalPort()))
            {
                // Set a very short read timeout so we can test if the server closed.
                socket1.setSoTimeout(1);
                InputStream input1 = socket1.getInputStream();

                assertTrue(_fileOnDirectoryMonitor.isLowOnResources());
                assertThrows(SocketTimeoutException.class, () -> input1.read());

                // Wait a couple of lowResources idleTimeouts.
                Thread.sleep(2 * lowResourcesIdleTimeout);

                // Verify the new socket is still open.
                assertTrue(_fileOnDirectoryMonitor.isLowOnResources());
                assertThrows(SocketTimeoutException.class, () -> input1.read());

                Files.delete(tmpFile);

                // Let the maxLowResourcesTime elapse.
                Thread.sleep(maxLowResourcesTime);
                assertFalse(_fileOnDirectoryMonitor.isLowOnResources());
            }
        }
    }

    static class FileOnDirectoryMonitor implements LowResourceMonitor.LowResourceCheck
    {
        private static final Logger LOG = Log.getLogger(FileOnDirectoryMonitor.class);

        private final Path _pathToMonitor;

        private String reason;

        public FileOnDirectoryMonitor(Path pathToMonitor)
        {
            _pathToMonitor = pathToMonitor;
        }

        @Override
        public boolean isLowOnResources()
        {
            try
            {
                Stream<Path> paths = Files.list(_pathToMonitor);
                List<Path> content = paths.collect(Collectors.toList());
                if (!content.isEmpty())
                {
                    reason = "directory not empty so enable low resources";
                    return true;
                }
            }
            catch (IOException e)
            {
                LOG.info("ignore issue looking at directory content", e);
            }
            return false;
        }

        @Override
        public String getReason()
        {
            return reason;
        }

        @Override
        public String toString()
        {
            return getClass().getName();
        }
    }
}
