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

package org.eclipse.jetty.ee.websocket.jakarta.common.messages;

import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.util.MethodHolder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DecodedTextStreamMessageSinkTest extends AbstractMessageSinkTest
{
    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Test
    public void testDate1Frame() throws Exception
    {
        CompletableFuture<Date> copyFuture = new CompletableFuture<>();
        DecodedDateCopy copy = new DecodedDateCopy(copyFuture);
        MethodHolder copyHandle = getAcceptHandle(copy, Date.class);
        List<RegisteredDecoder> decoders = toRegisteredDecoderList(GmtDecoder.class, Calendar.class);
        DecodedTextStreamMessageSink<Calendar> sink = new DecodedTextStreamMessageSink<>(session.getCoreSession(), copyHandle, decoders);

        try (Frame frame = new Frame(OpCode.TEXT, "2018.02.13"))
        {
            FutureCallback finCallback = new FutureCallback();
            sink.accept(frame, finCallback);
            coreSession.waitForDemand(1, TimeUnit.SECONDS);
            finCallback.get(1, TimeUnit.SECONDS);
        }

        Date decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("02-13-2018"));
    }

    @Test
    public void testDate3Frames() throws Exception
    {
        CompletableFuture<Date> copyFuture = new CompletableFuture<>();
        DecodedDateCopy copy = new DecodedDateCopy(copyFuture);
        MethodHolder copyHandle = getAcceptHandle(copy, Date.class);
        List<RegisteredDecoder> decoders = toRegisteredDecoderList(GmtDecoder.class, Calendar.class);
        DecodedTextStreamMessageSink<Calendar> sink = new DecodedTextStreamMessageSink<>(session.getCoreSession(), copyHandle, decoders);

        FutureCallback callback1 = new FutureCallback();
        FutureCallback callback2 = new FutureCallback();
        FutureCallback finCallback = new FutureCallback();

        try (Frame frame = new Frame(OpCode.TEXT, false, "2023"))
        {
            sink.accept(frame, callback1);
            coreSession.waitForDemand(1, TimeUnit.SECONDS);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, false, ".08"))
        {
            sink.accept(frame, callback2);
            coreSession.waitForDemand(1, TimeUnit.SECONDS);
        }
        try (Frame frame = new Frame(OpCode.CONTINUATION, true, ".22"))
        {
            sink.accept(frame, finCallback);
            coreSession.waitForDemand(1, TimeUnit.SECONDS);
        }

        Date decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("08-22-2023"));
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));
    }

    private String format(Date date, String formatPattern)
    {
        SimpleDateFormat format = new SimpleDateFormat(formatPattern);
        format.setTimeZone(GMT);
        return format.format(date);
    }

    public static class DecodedDateCopy implements Consumer<Date>
    {
        private final CompletableFuture<Date> copyFuture;

        public DecodedDateCopy(CompletableFuture<Date> copyFuture)
        {
            this.copyFuture = copyFuture;
        }

        @Override
        public void accept(Date date)
        {
            copyFuture.complete(date);
        }
    }

    @SuppressWarnings("Duplicates")
    public static class GmtDecoder implements Decoder.TextStream<Date>
    {

        @Override
        public Date decode(Reader reader) throws DecodeException
        {
            String content;
            try
            {
                content = IO.toString(reader);
            }
            catch (IOException e)
            {
                throw new DecodeException("", "Unable to read from Reader", e);
            }

            try
            {

                SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd");
                format.setTimeZone(GMT);
                return format.parse(content);
            }
            catch (ParseException e)
            {
                throw new DecodeException(content, e.getMessage(), e);
            }
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
