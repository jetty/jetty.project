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

package org.eclipse.jetty.websocket.jsr356.tests.coders;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import javax.websocket.DecodeException;

import org.junit.Test;

public class DecoderTextTest
{
    private TimeZone GMT = TimeZone.getTimeZone("GMT");

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
