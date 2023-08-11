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

package org.eclipse.jetty.util;

import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DateCacheTest
{
    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testDateCache() throws Exception
    {
        //@WAS: Test t = new Test("org.eclipse.jetty.util.DateCache");
        //                            012345678901234567890123456789
        DateCache dc = new DateCache("EEE, dd MMM yyyy HH:mm:ss zzz ZZZ", Locale.US, TimeZone.getTimeZone("GMT"));

        Thread.sleep(2000);

        Instant now = Instant.now();
        Instant end = now.plusSeconds(3);
        String f = dc.format(now.toEpochMilli());

        int hits = 0;
        int misses = 0;

        while (now.isBefore(end))
        {
            String last = f;
            f = dc.format(now.toEpochMilli());
            // System.err.printf("%s %s%n",f,last==f);
            if (last == f)
                hits++;
            else
                misses++;

            TimeUnit.MILLISECONDS.sleep(100);
            now = Instant.now();
        }
        assertThat(hits, Matchers.greaterThan(misses));
    }

    @Test
    public void testAllMethods()
    {
        // we simply check we do not have any exception
        DateCache dateCache = new DateCache();
        assertNotNull(dateCache.format(System.currentTimeMillis()));
        assertNotNull(dateCache.format(new Date().getTime()));
        assertNotNull(dateCache.format(Instant.now().toEpochMilli()));
        assertNotNull(dateCache.format(new Date()));
        assertNotNull(dateCache.format(new Date(System.currentTimeMillis())));

        assertNotNull(dateCache.formatTick(System.currentTimeMillis()));
        assertNotNull(dateCache.formatTick(new Date().getTime()));
        assertNotNull(dateCache.formatTick(Instant.now().toEpochMilli()));

        assertNotNull(dateCache.getFormatString());

        assertNotNull(dateCache.getTimeZone());

        assertNotNull(dateCache.now());

        assertNotNull(dateCache.tick());
    }

    @Test
    public void testChangeOfSecond() throws  Exception
    {
        AtomicInteger counter = new AtomicInteger();
        DateCache dateCache = new DateCache(DateCache.DEFAULT_FORMAT + " | SSS", null, TimeZone.getTimeZone("UTC"))
        {
            @Override
            public String formatWithoutCache(long inDate)
            {
                counter.incrementAndGet();
                return super.formatWithoutCache(inDate);
            }
        };


        for (int i = 0; i < 10; i++)
        {
            assertThat(format(dateCache, "2012-12-21T10:15:30.55Z"), equalTo("Fri Dec 21 10:15:30 UTC 2012 | 550"));
            assertThat(format(dateCache, "2012-12-21T10:15:31.33Z"), equalTo("Fri Dec 21 10:15:31 UTC 2012 | 330"));
        }
        assertThat(counter.get(), equalTo(2));
    }


    private static String format(DateCache dateCache, String instant)
    {
        return dateCache.format(Date.from(Instant.parse(instant)));
    }
}
