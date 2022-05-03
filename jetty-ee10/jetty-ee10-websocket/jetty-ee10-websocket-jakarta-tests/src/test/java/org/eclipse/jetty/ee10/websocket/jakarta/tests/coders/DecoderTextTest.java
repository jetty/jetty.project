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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import jakarta.websocket.DecodeException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DecoderTextTest
{
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Test
    public void testDateDecoder() throws DecodeException
    {
        DateDecoder decoder = new DateDecoder();
        Date date = decoder.decode("2018.02.12"); // yyyy.MM.dd
        Calendar cal = Calendar.getInstance(GMT);
        cal.setTime(date);
        assertThat("Date.year", cal.get(Calendar.YEAR), is(2018));
        assertThat("Date.month", cal.get(Calendar.MONTH), is(Calendar.FEBRUARY));
        assertThat("Date.day", cal.get(Calendar.DAY_OF_MONTH), is(12));
    }
}
