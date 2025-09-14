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

package org.eclipse.jetty.http2.tests;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Dumpable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2ConnectionDumpTest extends AbstractServerTest
{
    @BeforeEach
    public void setUp() throws Exception
    {
        startServer(new ServerSessionListener()
        {
            @Override
            public void onAccept(Session session)
            {
                session.settings(new SettingsFrame(Map.of(
                    SettingsFrame.MAX_CONCURRENT_STREAMS, 124,
                    SettingsFrame.MAX_FRAME_SIZE, 32768
                ), false), Callback.NOOP);
            }
        });
    }

    @Test
    public void testDumpSettingsLocalAndRemote() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setTcpNoDelay(true);
            client.setSoTimeout(5000);

            OutputStream output = client.getOutputStream();
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator, new PrefaceFrame());
            generator.control(accumulator, new SettingsFrame(Map.of(SettingsFrame.MAX_CONCURRENT_STREAMS, 128), false));
            accumulator.writeTo(Content.Sink.from(output), false);
            output.flush();

            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener() {});
            parseResponse(client, parser, 1000);
        }

        String dump = getConnectionDump();
        assertNotNull(dump);

        assertTrue(dump.contains("local settings"), dump);
        assertTrue(dump.contains("size=2"), dump);
        assertTrue(dump.contains("+> 3: 124"), dump);
        assertTrue(dump.contains("+> 5: 32768"), dump);

        assertTrue(dump.contains("remote settings"), dump);
        assertTrue(dump.contains("size=1"), dump);
        assertTrue(dump.contains("+> 3: 128"), dump);
    }

    @Test
    public void testDumpSettingsUpdate() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setTcpNoDelay(true);
            client.setSoTimeout(5000);

            OutputStream output = client.getOutputStream();
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener() {});

            RetainableByteBuffer.Mutable accumulator1 = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator1, new PrefaceFrame());
            generator.control(accumulator1, new SettingsFrame(Map.of(SettingsFrame.MAX_CONCURRENT_STREAMS, 128), false));
            accumulator1.writeTo(Content.Sink.from(output), false);
            output.flush();
            parseResponse(client, parser, 1000);

            RetainableByteBuffer.Mutable accumulator2 = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator2, new SettingsFrame(Map.of(
                SettingsFrame.ENABLE_CONNECT_PROTOCOL, 1
            ), false));
            accumulator2.writeTo(Content.Sink.from(output), false);
            output.flush();
            parseResponse(client, parser, 1000);
        }

        String dump = getConnectionDump();
        assertNotNull(dump);

        // New params are added and existing ones preserved.
        assertTrue(dump.contains("remote settings size=2"), dump);
        assertTrue(dump.contains("+> 3: 128"), dump);
        assertTrue(dump.contains("+> 8: 1"), dump);
    }

    @Test
    public void testDumpSettingsIgnoreEmpty() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setTcpNoDelay(true);
            client.setSoTimeout(5000);

            OutputStream output = client.getOutputStream();
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener() {});

            RetainableByteBuffer.Mutable acc1 = new RetainableByteBuffer.DynamicCapacity();
            generator.control(acc1, new PrefaceFrame());
            generator.control(acc1, new SettingsFrame(Map.of(SettingsFrame.MAX_CONCURRENT_STREAMS, 128), false));
            acc1.writeTo(Content.Sink.from(output), false);
            output.flush();
            parseResponse(client, parser, 1000);

            // Send empty SETTINGS (no-op).
            RetainableByteBuffer.Mutable acc2 = new RetainableByteBuffer.DynamicCapacity();
            generator.control(acc2, new SettingsFrame(Collections.emptyMap(), false));
            acc2.writeTo(Content.Sink.from(output), false);
            output.flush();
            parseResponse(client, parser, 1000);
        }

        String dump = getConnectionDump();
        assertNotNull(dump);

        // Unchanged local, remote settings.
        assertTrue(dump.contains("local settings size=2"), dump);
        assertTrue(dump.contains("+> 3: 124"), dump);
        assertTrue(dump.contains("+> 5: 32768"), dump);
        assertTrue(dump.contains("remote settings size=1"), dump);
        assertTrue(dump.contains("+> 3: 128"), dump);
    }

    @Test
    public void testDumpSettingsIgnoreAck() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setTcpNoDelay(true);
            client.setSoTimeout(5000);

            OutputStream output = client.getOutputStream();
            RetainableByteBuffer.Mutable accumulator1 = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator1, new PrefaceFrame());
            generator.control(accumulator1, new SettingsFrame(Map.of(SettingsFrame.MAX_CONCURRENT_STREAMS, 128), false));
            accumulator1.writeTo(Content.Sink.from(output), false);
            output.flush();

            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener() {});
            parseResponse(client, parser, 1000);

            // Sent ACK, remote to local.
            RetainableByteBuffer.Mutable accumulator2 = new RetainableByteBuffer.DynamicCapacity();
            generator.control(accumulator2, new SettingsFrame(Collections.emptyMap(), true));
            accumulator2.writeTo(Content.Sink.from(output), false);
            output.flush();
            parseResponse(client, parser, 1000);
        }

        String dump = getConnectionDump();
        assertNotNull(dump);

        // Unchanged local, remote settings.
        assertTrue(dump.contains("local settings size=2"), dump);
        assertTrue(dump.contains("+> 3: 124"), dump);
        assertTrue(dump.contains("+> 5: 32768"), dump);
        assertTrue(dump.contains("remote settings size=1"), dump);
        assertTrue(dump.contains("+> 3: 128"), dump);
    }

    private String getConnectionDump() throws IOException
    {
        for (EndPoint ep : connector.getConnectedEndPoints())
        {
            Connection conn = ep.getConnection();
            if (conn instanceof Dumpable d)
            {
                StringBuilder sb = new StringBuilder();
                d.dump(sb, "");
                return sb.toString();
            }
        }
        return null;
    }
}
