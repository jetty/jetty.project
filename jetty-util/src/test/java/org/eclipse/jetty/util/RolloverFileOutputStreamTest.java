//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.TimeZone;

import org.junit.Test;

public class RolloverFileOutputStreamTest
{
    private static ZoneId toZoneId(String timezoneId)
    {
        ZoneId zone = TimeZone.getTimeZone(timezoneId).toZoneId();
        // System.out.printf(".toZoneId(\"%s\") = [id=%s,normalized=%s]%n", timezoneId, zone.getId(), zone.normalized());
        return zone;
    }
    
    private static ZonedDateTime toDateTime(String timendate, ZoneId zone)
    {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd-hh:mm:ss.S a z")
                .withZone(zone);
        return ZonedDateTime.parse(timendate, formatter);
    }
    
    private static String toString(TemporalAccessor date)
    {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd-hh:mm:ss.S a z");
        return formatter.format(date);
    }
    
    private void assertSequence(ZonedDateTime midnight, Object[][] expected)
    {
        ZonedDateTime nextEvent = midnight;
        
        for (int i = 0; i < expected.length; i++)
        {
            long currentMillis = nextEvent.toInstant().toEpochMilli();
            nextEvent = nextEvent.toLocalDate().plus(1, ChronoUnit.DAYS).atStartOfDay(nextEvent.getZone());                
            assertThat("Next Event", toString(nextEvent), is(expected[i][0]));
            long duration = (nextEvent.toInstant().toEpochMilli() - currentMillis);
            assertThat("Duration to next event", duration, is((long) expected[i][1]));
        }
    }
    
    /**
     * <a href="Issue #1507">https://github.com/eclipse/jetty.project/issues/1507</a>
     */
    @Test
    public void testMidnightRolloverCalc_PDT_Issue1507()
    {
        ZoneId zone = toZoneId("PST");
        ZonedDateTime initialDate = toDateTime("2017.04.27-08:00:00.0 PM PDT", zone);
        
        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2017.04.27-12:00:00.0 AM PDT"));
        
        Object expected[][] = {
                {"2017.04.28-12:00:00.0 AM PDT", 14_400_000L}, // Ensure not negative
                {"2017.04.29-12:00:00.0 AM PDT", 86_400_000L},
                {"2017.04.30-12:00:00.0 AM PDT", 86_400_000L},
                {"2017.05.01-12:00:00.0 AM PDT", 86_400_000L},
                {"2017.05.02-12:00:00.0 AM PDT", 86_400_000L},
        };
        
        assertSequence(initialDate, expected);
    }

    @Test
    public void testMidnightRolloverCalc_PST_DST_Start()
    {
        ZoneId zone = toZoneId("PST");
        ZonedDateTime initialDate = toDateTime("2016.03.11-01:23:45.0 PM PST", zone);
        
        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.03.11-12:00:00.0 AM PST"));
        
        Object expected[][] = {
                {"2016.03.12-12:00:00.0 AM PST", 86_400_000L},
                {"2016.03.13-12:00:00.0 AM PST", 86_400_000L},
                {"2016.03.14-12:00:00.0 AM PDT", 82_800_000L}, // the short day
                {"2016.03.15-12:00:00.0 AM PDT", 86_400_000L},
                {"2016.03.16-12:00:00.0 AM PDT", 86_400_000L},
        };
    
        assertSequence(midnight, expected);
    }
    
    @Test
    public void testMidnightRolloverCalc_PST_DST_End()
    {
        ZoneId zone = toZoneId("PST");
        ZonedDateTime initialDate = toDateTime("2016.11.04-11:22:33.0 AM PDT", zone);
    
        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.11.04-12:00:00.0 AM PDT"));
    
        Object expected[][] = {
                {"2016.11.05-12:00:00.0 AM PDT", 86_400_000L},
                {"2016.11.06-12:00:00.0 AM PDT", 86_400_000L},
                {"2016.11.07-12:00:00.0 AM PST", 90_000_000L}, // the long day
                {"2016.11.08-12:00:00.0 AM PST", 86_400_000L},
                {"2016.11.09-12:00:00.0 AM PST", 86_400_000L},
        };
    
        assertSequence(midnight, expected);
    }
    
    @Test
    public void testMidnightRolloverCalc_Sydney_DST_Start()
    {
        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime initialDate = toDateTime("2016.10.01-01:23:45.0 PM AEST", zone);
    
        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.10.01-12:00:00.0 AM AEST"));
    
        Object expected[][] = {
                {"2016.10.02-12:00:00.0 AM AEST", 86_400_000L},
                {"2016.10.03-12:00:00.0 AM AEDT", 82_800_000L}, // the short day
                {"2016.10.04-12:00:00.0 AM AEDT", 86_400_000L},
                {"2016.10.05-12:00:00.0 AM AEDT", 86_400_000L},
                {"2016.10.06-12:00:00.0 AM AEDT", 86_400_000L},
        };
        
        assertSequence(midnight, expected);
    }
    
    @Test
    public void testMidnightRolloverCalc_Sydney_DST_End()
    {
        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime initialDate = toDateTime("2016.04.02-11:22:33.0 AM AEDT", zone);
    
        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.04.02-12:00:00.0 AM AEDT"));
    
        Object expected[][] = {
                {"2016.04.03-12:00:00.0 AM AEDT", 86_400_000L},
                {"2016.04.04-12:00:00.0 AM AEST", 90_000_000L}, // The long day
                {"2016.04.05-12:00:00.0 AM AEST", 86_400_000L},
                {"2016.04.06-12:00:00.0 AM AEST", 86_400_000L},
                {"2016.04.07-12:00:00.0 AM AEST", 86_400_000L},
        };
    
        assertSequence(midnight, expected);
    }
}
