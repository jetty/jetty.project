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

package org.eclipse.jetty.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class RolloverFileOutputStreamTest
{
    public WorkDir testingDir;

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
            assertThat("Duration to next event", duration, is((long)expected[i][1]));
        }
    }

    /**
     * <a href="Issue #1507">https://github.com/eclipse/jetty.project/issues/1507</a>
     */
    @Test
    public void testMidnightRolloverCalcPDTIssue1507()
    {
        ZoneId zone = toZoneId("PST");
        ZonedDateTime initialDate = toDateTime("2017.04.26-08:00:00.0 PM PDT", zone);

        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2017.04.27-12:00:00.0 AM PDT"));

        Object[][] expected = {
            {"2017.04.27-12:00:00.0 AM PDT", 14_400_000L},
            {"2017.04.28-12:00:00.0 AM PDT", 86_400_000L},
            {"2017.04.29-12:00:00.0 AM PDT", 86_400_000L},
            {"2017.04.30-12:00:00.0 AM PDT", 86_400_000L},
            {"2017.05.01-12:00:00.0 AM PDT", 86_400_000L},
            {"2017.05.02-12:00:00.0 AM PDT", 86_400_000L},
            };

        assertSequence(initialDate, expected);
    }

    @Test
    public void testMidnightRolloverCalcPSTDSTStart()
    {
        ZoneId zone = toZoneId("PST");
        ZonedDateTime initialDate = toDateTime("2016.03.10-01:23:45.0 PM PST", zone);

        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.03.11-12:00:00.0 AM PST"));

        Object[][] expected = {
            {"2016.03.12-12:00:00.0 AM PST", 86_400_000L},
            {"2016.03.13-12:00:00.0 AM PST", 86_400_000L},
            {"2016.03.14-12:00:00.0 AM PDT", 82_800_000L}, // the short day
            {"2016.03.15-12:00:00.0 AM PDT", 86_400_000L},
            {"2016.03.16-12:00:00.0 AM PDT", 86_400_000L},
            };

        assertSequence(midnight, expected);
    }

    @Test
    public void testMidnightRolloverCalcPSTDSTEnd()
    {
        ZoneId zone = toZoneId("PST");
        ZonedDateTime initialDate = toDateTime("2016.11.03-11:22:33.0 AM PDT", zone);

        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.11.04-12:00:00.0 AM PDT"));

        Object[][] expected = {
            {"2016.11.05-12:00:00.0 AM PDT", 86_400_000L},
            {"2016.11.06-12:00:00.0 AM PDT", 86_400_000L},
            {"2016.11.07-12:00:00.0 AM PST", 90_000_000L}, // the long day
            {"2016.11.08-12:00:00.0 AM PST", 86_400_000L},
            {"2016.11.09-12:00:00.0 AM PST", 86_400_000L},
            };

        assertSequence(midnight, expected);
    }

    @Test
    public void testMidnightRolloverCalcSydneyDSTStart()
    {
        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime initialDate = toDateTime("2016.09.31-01:23:45.0 PM AEST", zone);

        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.10.01-12:00:00.0 AM AEST"));

        Object[][] expected = {
            {"2016.10.02-12:00:00.0 AM AEST", 86_400_000L},
            {"2016.10.03-12:00:00.0 AM AEDT", 82_800_000L}, // the short day
            {"2016.10.04-12:00:00.0 AM AEDT", 86_400_000L},
            {"2016.10.05-12:00:00.0 AM AEDT", 86_400_000L},
            {"2016.10.06-12:00:00.0 AM AEDT", 86_400_000L},
            };

        assertSequence(midnight, expected);
    }

    @Test
    public void testMidnightRolloverCalcSydneyDSTEnd()
    {
        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime initialDate = toDateTime("2016.04.01-11:22:33.0 AM AEDT", zone);

        ZonedDateTime midnight = RolloverFileOutputStream.toMidnight(initialDate);
        assertThat("Midnight", toString(midnight), is("2016.04.02-12:00:00.0 AM AEDT"));

        Object[][] expected = {
            {"2016.04.03-12:00:00.0 AM AEDT", 86_400_000L},
            {"2016.04.04-12:00:00.0 AM AEST", 90_000_000L}, // The long day
            {"2016.04.05-12:00:00.0 AM AEST", 86_400_000L},
            {"2016.04.06-12:00:00.0 AM AEST", 86_400_000L},
            {"2016.04.07-12:00:00.0 AM AEST", 86_400_000L},
            };

        assertSequence(midnight, expected);
    }

    @Test
    public void testFileHandling() throws Exception
    {
        Path testPath = testingDir.getEmptyPathDir();

        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime now = toDateTime("2016.04.10-08:30:12.3 AM AEDT", zone);

        Path template = testPath.resolve("test-rofos-yyyy_mm_dd.log");
        String templateString = template.toAbsolutePath().toString();

        try (RolloverFileOutputStream rofos =
                 new RolloverFileOutputStream(templateString, false, 3, TimeZone.getTimeZone(zone), null, null, now))
        {
            rofos.write("TICK".getBytes());
            rofos.flush();
        }

        now = now.plus(5, ChronoUnit.MINUTES);

        try (RolloverFileOutputStream rofos =
                 new RolloverFileOutputStream(templateString, false, 3, TimeZone.getTimeZone(zone), null, null, now))
        {
            rofos.write("TOCK".getBytes());
            rofos.flush();
            String[] ls = ls(testPath);
            assertThat(ls.length, is(2));
            String backup = null;
            for (String n : ls)
            {
                if (!"test-rofos-2016_04_10.log".equals(n))
                    backup = n;
            }

            assertThat(Arrays.asList(ls), Matchers.containsInAnyOrder(backup, "test-rofos-2016_04_10.log"));

            Files.setLastModifiedTime(testPath.resolve(backup), FileTime.from(now.toInstant()));
            Files.setLastModifiedTime(testPath.resolve("test-rofos-2016_04_10.log"), FileTime.from(now.toInstant()));

            ZonedDateTime time = now.minus(1, ChronoUnit.DAYS);
            for (int i = 10; i-- > 5; )
            {
                String file = "test-rofos-2016_04_0" + i + ".log";
                Path path = testPath.resolve(file);
                FS.touch(path);
                Files.setLastModifiedTime(path, FileTime.from(time.toInstant()));

                if (i % 2 == 0)
                {
                    file = "test-rofos-2016_04_0" + i + ".log.083512300";
                    path = testPath.resolve(file);
                    FS.touch(path);
                    Files.setLastModifiedTime(path, FileTime.from(time.toInstant()));
                    time = time.minus(1, ChronoUnit.DAYS);
                }

                file = "unrelated-" + i;
                path = testPath.resolve(file);
                FS.touch(path);
                Files.setLastModifiedTime(path, FileTime.from(time.toInstant()));

                time = time.minus(1, ChronoUnit.DAYS);
            }

            ls = ls(testPath);
            assertThat(ls.length, is(14));
            assertThat(Arrays.asList(ls), Matchers.containsInAnyOrder(
                "test-rofos-2016_04_05.log",
                "test-rofos-2016_04_06.log",
                "test-rofos-2016_04_07.log",
                "test-rofos-2016_04_08.log",
                "test-rofos-2016_04_09.log",
                "test-rofos-2016_04_10.log",
                "test-rofos-2016_04_06.log.083512300",
                "test-rofos-2016_04_08.log.083512300",
                "test-rofos-2016_04_10.log.083512300",
                "unrelated-9",
                "unrelated-8",
                "unrelated-7",
                "unrelated-6",
                "unrelated-5"
            ));

            rofos.removeOldFiles(now);
            ls = ls(testPath);
            assertThat(ls.length, is(10));
            assertThat(Arrays.asList(ls), Matchers.containsInAnyOrder(
                "test-rofos-2016_04_08.log",
                "test-rofos-2016_04_09.log",
                "test-rofos-2016_04_10.log",
                "test-rofos-2016_04_08.log.083512300",
                "test-rofos-2016_04_10.log.083512300",
                "unrelated-9",
                "unrelated-8",
                "unrelated-7",
                "unrelated-6",
                "unrelated-5"));

            assertThat(readPath(testPath.resolve(backup)), is("TICK"));
            assertThat(readPath(testPath.resolve("test-rofos-2016_04_10.log")), is("TOCK"));
        }
    }

    @Test
    public void testRollover() throws Exception
    {
        Path testPath = testingDir.getEmptyPathDir();

        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime now = toDateTime("2016.04.10-11:59:55.0 PM AEDT", zone);

        Path template = testPath.resolve("test-rofos-yyyy_mm_dd.log");
        String templateString = template.toAbsolutePath().toString();

        try (RolloverFileOutputStream rofos =
                 new RolloverFileOutputStream(templateString, false, 0, TimeZone.getTimeZone(zone), null, null, now))
        {
            rofos.write("BEFORE".getBytes());
            rofos.flush();
            String[] ls = ls(testPath);
            assertThat(ls.length, is(1));
            assertThat(ls[0], is("test-rofos-2016_04_10.log"));

            TimeUnit.SECONDS.sleep(10);
            rofos.write("AFTER".getBytes());
            ls = ls(testPath);
            assertThat(ls.length, is(2));

            for (String n : ls)
            {
                String content = readPath(testPath.resolve(n));
                if ("test-rofos-2016_04_10.log".equals(n))
                {
                    assertThat(content, is("BEFORE"));
                }
                else
                {
                    assertThat(content, is("AFTER"));
                }
            }
        }
    }

    @Test
    public void testRolloverBackup() throws Exception
    {
        Path testPath = testingDir.getEmptyPathDir();

        ZoneId zone = toZoneId("Australia/Sydney");
        ZonedDateTime now = toDateTime("2016.04.10-11:59:55.0 PM AEDT", zone);

        Path template = testPath.resolve("test-rofosyyyy_mm_dd.log");
        String templateString = template.toAbsolutePath().toString();

        try (RolloverFileOutputStream rofos =
                 new RolloverFileOutputStream(templateString, false, 0, TimeZone.getTimeZone(zone), "", null, now))
        {
            rofos.write("BEFORE".getBytes());
            rofos.flush();
            String[] ls = ls(testPath);
            assertThat("File List.length", ls.length, is(1));
            assertThat(ls[0], is("test-rofos.log"));

            TimeUnit.SECONDS.sleep(10);
            rofos.write("AFTER".getBytes());
            ls = ls(testPath);
            assertThat("File List.length", ls.length, is(2));

            for (String n : ls)
            {
                String content = readPath(testPath.resolve(n));
                if ("test-rofos.log".equals(n))
                {
                    assertThat(content, is("AFTER"));
                }
                else
                {
                    assertThat(content, is("BEFORE"));
                }
            }
        }
    }

    private String readPath(Path path) throws IOException
    {
        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            return IO.toString(reader);
        }
    }

    private String[] ls(Path path) throws IOException
    {
        try (Stream<Path> s = Files.list(path))
        {
            return s.map(p -> p.getFileName().toString()).toArray(String[]::new);
        }
    }
}
