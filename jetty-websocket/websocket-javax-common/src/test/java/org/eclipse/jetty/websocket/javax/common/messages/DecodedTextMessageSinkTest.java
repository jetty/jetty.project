//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.messages;

import java.lang.invoke.MethodHandle;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.common.CompletableFutureCallback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DecodedTextMessageSinkTest extends AbstractMessageSinkTest
{
    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Test
    public void testDate1Frame() throws Exception
    {
        CompletableFuture<Date> copyFuture = new CompletableFuture<>();
        DecodedDateCopy copy = new DecodedDateCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Date.class);
        Decoder.Text<Date> decoder = new GmtDecoder();
        DecodedTextMessageSink sink = new DecodedTextMessageSink(session, decoder, copyHandle);

        CompletableFutureCallback finCallback = new CompletableFutureCallback();
        sink.accept(new Frame(OpCode.TEXT).setPayload("2018.02.13").setFin(true), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        Date decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("02-13-2018"));
    }

    @Test
    public void testDate3Frames() throws Exception
    {
        CompletableFuture<Date> copyFuture = new CompletableFuture<>();
        DecodedDateCopy copy = new DecodedDateCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Date.class);
        Decoder.Text<Date> decoder = new GmtDecoder();
        DecodedTextMessageSink sink = new DecodedTextMessageSink(session, decoder, copyHandle);

        CompletableFutureCallback callback1 = new CompletableFutureCallback();
        CompletableFutureCallback callback2 = new CompletableFutureCallback();
        CompletableFutureCallback finCallback = new CompletableFutureCallback();

        sink.accept(new Frame(OpCode.TEXT).setPayload("2023").setFin(false), callback1);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(".08").setFin(false), callback2);
        sink.accept(new Frame(OpCode.CONTINUATION).setPayload(".22").setFin(true), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        Date decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("Callback1.done", callback1.isDone(), is(true));
        assertThat("Callback2.done", callback2.isDone(), is(true));
        assertThat("finCallback.done", finCallback.isDone(), is(true));

        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("08-22-2023"));
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
    public static class GmtDecoder implements Decoder.Text<Date>
    {

        @Override
        public Date decode(String s) throws DecodeException
        {
            try
            {
                SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd");
                format.setTimeZone(GMT);
                return format.parse(s);
            }
            catch (ParseException e)
            {
                throw new DecodeException(s, e.getMessage(), e);
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

        @Override
        public boolean willDecode(String s)
        {
            return true;
        }
    }
}
