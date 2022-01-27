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

package org.eclipse.jetty.websocket.core.extensions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.DemandingIncomingFramesCapture;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.IncomingFramesCapture;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingFramesCapture;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.FragmentExtension;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class FragmentExtensionTest extends AbstractExtensionTest
{
    /**
     * Verify that incoming frames are fragmented correctly.
     */
    @Test
    public void testIncomingFrames() throws Exception
    {
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=20");
        WebSocketCoreSession coreSession = newSession(config);
        FragmentExtension ext = (FragmentExtension)coreSession.getExtensionStack().getExtensions().get(0);

        IncomingFramesCapture capture = new DemandingIncomingFramesCapture(coreSession);
        ext.setNextIncomingFrames(capture);

        // Simulate initial demand from onOpen().
        coreSession.autoDemand();

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Write quote as separate frames
        for (String section : quote)
        {
            Frame frame = new Frame(OpCode.TEXT).setPayload(section);
            ext.onFrame(frame, Callback.NOOP);
        }

        // Expected Frames
        List<Frame> expectedFrames = new ArrayList<>();
        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("No amount of experim").setFin(false));
        expectedFrames.add(new Frame(OpCode.CONTINUATION).setPayload("entation can ever pr").setFin(false));
        expectedFrames.add(new Frame(OpCode.CONTINUATION).setPayload("ove me right;").setFin(true));

        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("a single experiment ").setFin(false));
        expectedFrames.add(new Frame(OpCode.CONTINUATION).setPayload("can prove me wrong.").setFin(true));

        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("-- Albert Einstein").setFin(true));

        // capture.dump();

        int len = expectedFrames.size();
        capture.assertFrameCount(len);

        String prefix;
        BlockingQueue<Frame> frames = capture.frames;
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";
            Frame actualFrame = frames.poll(1, TimeUnit.SECONDS);
            Frame expectedFrame = expectedFrames.get(i);

            // System.out.printf("actual: %s%n",actualFrame);
            // System.out.printf("expect: %s%n",expectedFrame);

            // Validate Frame
            assertThat(prefix + ".opcode", actualFrame.getOpCode(), is(expectedFrame.getOpCode()));
            assertThat(prefix + ".fin", actualFrame.isFin(), is(expectedFrame.isFin()));
            assertThat(prefix + ".rsv1", actualFrame.isRsv1(), is(expectedFrame.isRsv1()));
            assertThat(prefix + ".rsv2", actualFrame.isRsv2(), is(expectedFrame.isRsv2()));
            assertThat(prefix + ".rsv3", actualFrame.isRsv3(), is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            assertThat(prefix + ".payloadLength", actualData.remaining(), is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expectedData, actualData);
        }
    }

    /**
     * Incoming PING (Control Frame) should pass through extension unmodified
     */
    @Test
    public void testIncomingPing()
    {
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        WebSocketCoreSession coreSession = newSession(config);
        FragmentExtension ext = (FragmentExtension)coreSession.getExtensionStack().getExtensions().get(0);

        IncomingFramesCapture capture = new DemandingIncomingFramesCapture(coreSession);
        ext.setNextIncomingFrames(capture);

        // Simulate initial demand from onOpen().
        coreSession.autoDemand();

        String payload = "Are you there?";
        Frame ping = new Frame(OpCode.PING).setPayload(payload);
        ext.onFrame(ping, Callback.NOOP);

        capture.assertFrameCount(1);
        capture.assertHasOpCount(OpCode.PING, 1);
        Frame actual = capture.frames.poll();

        assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.PING));
        assertThat("Frame.fin", actual.isFin(), is(true));
        assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload, StandardCharsets.UTF_8);
        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload", expected, actual.getPayload().slice());
    }

    /**
     * Verify that outgoing text frames are fragmented by the maxLength configuration.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testOutgoingFramesByMaxLength() throws Exception
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=20");
        ext.init(config, components);

        ext.setNextOutgoingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Write quote as separate frames
        for (String section : quote)
        {
            Frame frame = new Frame(OpCode.TEXT).setPayload(section);
            ext.sendFrame(frame, null, false);
        }

        // Expected Frames
        List<Frame> expectedFrames = new ArrayList<>();
        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("No amount of experim").setFin(false));
        expectedFrames.add(new Frame(OpCode.CONTINUATION).setPayload("entation can ever pr").setFin(false));
        expectedFrames.add(new Frame(OpCode.CONTINUATION).setPayload("ove me right;").setFin(true));

        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("a single experiment ").setFin(false));
        expectedFrames.add(new Frame(OpCode.CONTINUATION).setPayload("can prove me wrong.").setFin(true));

        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("-- Albert Einstein").setFin(true));

        // capture.dump();

        int len = expectedFrames.size();
        capture.assertFrameCount(len);

        String prefix;
        BlockingQueue<Frame> frames = capture.frames;
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";
            Frame actualFrame = frames.poll(1, TimeUnit.SECONDS);
            Frame expectedFrame = expectedFrames.get(i);

            // System.out.printf("actual: %s%n",actualFrame);
            // System.out.printf("expect: %s%n",expectedFrame);

            // Validate Frame
            assertThat(prefix + ".opcode", actualFrame.getOpCode(), is(expectedFrame.getOpCode()));
            assertThat(prefix + ".fin", actualFrame.isFin(), is(expectedFrame.isFin()));
            assertThat(prefix + ".rsv1", actualFrame.isRsv1(), is(expectedFrame.isRsv1()));
            assertThat(prefix + ".rsv2", actualFrame.isRsv2(), is(expectedFrame.isRsv2()));
            assertThat(prefix + ".rsv3", actualFrame.isRsv3(), is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            assertThat(prefix + ".payloadLength", actualData.remaining(), is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expectedData, actualData);
        }
    }

    /**
     * Verify that outgoing text frames are fragmented by default configuration
     *
     * @throws Exception on test failure
     */
    @Test
    public void testOutgoingFramesDefaultConfig() throws Exception
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ExtensionConfig config = ExtensionConfig.parse("fragment");
        ext.init(config, components);

        ext.setNextOutgoingFrames(capture);

        // Quote
        List<String> quote = new ArrayList<>();
        quote.add("No amount of experimentation can ever prove me right;");
        quote.add("a single experiment can prove me wrong.");
        quote.add("-- Albert Einstein");

        // Write quote as separate frames
        for (String section : quote)
        {
            Frame frame = new Frame(OpCode.TEXT).setPayload(section);
            ext.sendFrame(frame, null, false);
        }

        // Expected Frames
        List<Frame> expectedFrames = new ArrayList<>();
        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("No amount of experimentation can ever prove me right;"));
        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("a single experiment can prove me wrong."));
        expectedFrames.add(new Frame(OpCode.TEXT).setPayload("-- Albert Einstein"));

        // capture.dump();

        int len = expectedFrames.size();
        capture.assertFrameCount(len);

        String prefix;
        BlockingQueue<Frame> frames = capture.frames;
        for (int i = 0; i < len; i++)
        {
            prefix = "Frame[" + i + "]";
            Frame actualFrame = frames.poll(1, TimeUnit.SECONDS);
            Frame expectedFrame = expectedFrames.get(i);

            // Validate Frame
            assertThat(prefix + ".opcode", actualFrame.getOpCode(), is(expectedFrame.getOpCode()));
            assertThat(prefix + ".fin", actualFrame.isFin(), is(expectedFrame.isFin()));
            assertThat(prefix + ".rsv1", actualFrame.isRsv1(), is(expectedFrame.isRsv1()));
            assertThat(prefix + ".rsv2", actualFrame.isRsv2(), is(expectedFrame.isRsv2()));
            assertThat(prefix + ".rsv3", actualFrame.isRsv3(), is(expectedFrame.isRsv3()));

            // Validate Payload
            ByteBuffer expectedData = expectedFrame.getPayload().slice();
            ByteBuffer actualData = actualFrame.getPayload().slice();

            assertThat(prefix + ".payloadLength", actualData.remaining(), is(expectedData.remaining()));
            ByteBufferAssert.assertEquals(prefix + ".payload", expectedData, actualData);
        }
    }

    /**
     * Outgoing PING (Control Frame) should pass through extension unmodified
     *
     * @throws IOException on test failure
     */
    @Test
    public void testOutgoingPing() throws IOException
    {
        OutgoingFramesCapture capture = new OutgoingFramesCapture();

        FragmentExtension ext = new FragmentExtension();
        ExtensionConfig config = ExtensionConfig.parse("fragment;maxLength=4");
        ext.init(config, components);

        ext.setNextOutgoingFrames(capture);

        String payload = "Are you there?";
        Frame ping = new Frame(OpCode.PING).setPayload(payload);

        ext.sendFrame(ping, null, false);

        capture.assertFrameCount(1);

        Frame actual = capture.frames.poll();

        assertThat("Frame.opcode", actual.getOpCode(), is(OpCode.PING));
        assertThat("Frame.fin", actual.isFin(), is(true));
        assertThat("Frame.rsv1", actual.isRsv1(), is(false));
        assertThat("Frame.rsv2", actual.isRsv2(), is(false));
        assertThat("Frame.rsv3", actual.isRsv3(), is(false));

        ByteBuffer expected = BufferUtil.toBuffer(payload, StandardCharsets.UTF_8);
        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(expected.remaining()));
        ByteBufferAssert.assertEquals("Frame.payload", expected, actual.getPayload().slice());
    }

    private WebSocketCoreSession newSession(ExtensionConfig config)
    {
        return newSessionFromConfig(new Configuration.ConfigurationCustomizer(), config == null ? Collections.emptyList() : Collections.singletonList(config));
    }

    private WebSocketCoreSession newSessionFromConfig(Configuration.ConfigurationCustomizer configuration, List<ExtensionConfig> configs)
    {
        ExtensionStack exStack = new ExtensionStack(components, Behavior.SERVER);
        exStack.negotiate(configs, configs);
        exStack.setLastDemand(l -> {}); // Never delegate to WebSocketConnection as it is null for this test.
        WebSocketCoreSession coreSession = new WebSocketCoreSession(new TestMessageHandler(), Behavior.SERVER, Negotiated.from(exStack), components);
        configuration.customize(configuration);
        return coreSession;
    }
}
