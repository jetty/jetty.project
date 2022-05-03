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

package org.eclipse.jetty.ee10.websocket.jakarta.common.messages;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DecodedBinaryStreamMessageSinkTest extends AbstractMessageSinkTest
{
    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Test
    public void testCalendar1Frame() throws Exception
    {
        CompletableFuture<Calendar> copyFuture = new CompletableFuture<>();
        DecodedCalendarCopy copy = new DecodedCalendarCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Calendar.class);
        List<RegisteredDecoder> decoders = toRegisteredDecoderList(GmtDecoder.class, Calendar.class);
        DecodedBinaryStreamMessageSink<Calendar> sink = new DecodedBinaryStreamMessageSink<>(session.getCoreSession(), copyHandle, decoders);

        FutureCallback finCallback = new FutureCallback();
        ByteBuffer data = ByteBuffer.allocate(16);
        data.putShort((short)1999);
        data.put((byte)12);
        data.put((byte)31);
        data.flip();
        sink.accept(new Frame(OpCode.BINARY).setPayload(data).setFin(true), finCallback);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);

        Calendar decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("12-31-1999"));
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
    }

    @Test
    public void testCalendar3Frames() throws Exception
    {
        CompletableFuture<Calendar> copyFuture = new CompletableFuture<>();
        DecodedCalendarCopy copy = new DecodedCalendarCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Calendar.class);
        List<RegisteredDecoder> decoders = toRegisteredDecoderList(GmtDecoder.class, Calendar.class);
        DecodedBinaryStreamMessageSink<Calendar> sink = new DecodedBinaryStreamMessageSink<>(session.getCoreSession(), copyHandle, decoders);

        FutureCallback callback1 = new FutureCallback();
        FutureCallback callback2 = new FutureCallback();
        FutureCallback finCallback = new FutureCallback();

        ByteBuffer data1 = ByteBuffer.allocate(16);
        data1.putShort((short)2000);
        data1.flip();

        ByteBuffer data2 = ByteBuffer.allocate(16);
        data2.put((byte)1);
        data2.flip();

        ByteBuffer data3 = ByteBuffer.allocate(16);
        data3.put((byte)1);
        data3.flip();

        sink.accept(new Frame(OpCode.BINARY).setPayload(data1).setFin(false), callback1);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(data2).setFin(false), callback2);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(data3).setFin(true), finCallback);
        coreSession.waitForDemand(1, TimeUnit.SECONDS);

        Calendar decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("01-01-2000"));
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
    }

    private String format(Calendar cal, String formatPattern)
    {
        SimpleDateFormat format = new SimpleDateFormat(formatPattern);
        format.setTimeZone(GMT);
        return format.format(cal.getTime());
    }

    public static class DecodedCalendarCopy implements Consumer<Calendar>
    {
        private final CompletableFuture<Calendar> copyFuture;

        public DecodedCalendarCopy(CompletableFuture<Calendar> copyFuture)
        {
            this.copyFuture = copyFuture;
        }

        @Override
        public void accept(Calendar cal)
        {
            copyFuture.complete(cal);
        }
    }

    @SuppressWarnings("Duplicates")
    public static class GmtDecoder implements Decoder.BinaryStream<Calendar>
    {
        @Override
        public Calendar decode(InputStream stream) throws DecodeException
        {
            ByteBuffer buffer = ByteBuffer.allocate(16);
            int needed = 4;
            try
            {
                BufferUtil.readFrom(stream, needed, buffer);
                buffer.flip();
            }
            catch (IOException e)
            {
                throw new DecodeException("", "Unable to read from InputStream", e);
            }

            Calendar cal = Calendar.getInstance(GMT);
            cal.set(Calendar.YEAR, buffer.getShort());
            cal.set(Calendar.MONTH, buffer.get() - 1);
            cal.set(Calendar.DAY_OF_MONTH, buffer.get());
            return cal;
        }

        @Override
        public void destroy()
        {
        }

        @Override
        public void init(EndpointConfig config)
        {
        }
    }
}
