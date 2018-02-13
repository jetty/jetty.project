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

package org.eclipse.jetty.websocket.jsr356.messages;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

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

import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.CompletableFutureCallback;
import org.junit.Test;

public class DecodedTextMessageSinkTest extends AbstractMessageSinkTest
{
    public final static TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Test
    public void testDate_1_Frame() throws Exception
    {
        CompletableFuture<Date> copyFuture = new CompletableFuture<>();
        DecodedDateCopy copy = new DecodedDateCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Date.class);
        Decoder.Text<Date> decoder = new GmtDecoder();
        DecodedTextMessageSink sink = new DecodedTextMessageSink(session, decoder, copyHandle);

        CompletableFutureCallback finCallback = new CompletableFutureCallback();
        sink.accept(new TextFrame().setPayload("2018.02.13").setFin(true), finCallback);

        finCallback.get(1, TimeUnit.SECONDS); // wait for callback
        Date decoded = copyFuture.get(1, TimeUnit.SECONDS);
        assertThat("FinCallback.done", finCallback.isDone(), is(true));
        assertThat("Decoded.contents", format(decoded, "MM-dd-yyyy"), is("02-13-2018"));
    }

    @Test
    public void testDate_3_Frames() throws Exception
    {
        CompletableFuture<Date> copyFuture = new CompletableFuture<>();
        DecodedDateCopy copy = new DecodedDateCopy(copyFuture);
        MethodHandle copyHandle = getAcceptHandle(copy, Date.class);
        Decoder.Text<Date> decoder = new GmtDecoder();
        DecodedTextMessageSink sink = new DecodedTextMessageSink(session, decoder, copyHandle);

        CompletableFutureCallback callback1 = new CompletableFutureCallback();
        CompletableFutureCallback callback2 = new CompletableFutureCallback();
        CompletableFutureCallback finCallback = new CompletableFutureCallback();

        sink.accept(new TextFrame().setPayload("2023").setFin(false), callback1);
        sink.accept(new ContinuationFrame().setPayload(".08").setFin(false), callback2);
        sink.accept(new ContinuationFrame().setPayload(".22").setFin(true), finCallback);

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
