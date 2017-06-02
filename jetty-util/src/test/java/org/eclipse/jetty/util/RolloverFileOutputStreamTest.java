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

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class RolloverFileOutputStreamTest
{
    @Rule
    public TestingDir testingDir = new TestingDir();
    
    private static TimeZone toZoneId(String timezoneId)
    {
        TimeZone zone = TimeZone.getTimeZone(timezoneId);
        // System.err.printf("toZoneId('%s'): displayName=%s, id=%s%n", timezoneId, zone.getDisplayName(), zone.getID());
        return zone;
    }
    
    private static Calendar toDateTime(String timendate, TimeZone zone) throws ParseException
    {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy.MM.dd-hh:mm:ss.S a z");
        formatter.setTimeZone(zone);
        Date parsed = formatter.parse(timendate);
        Calendar cal = Calendar.getInstance(zone);
        cal.setTime(parsed);
        return cal;
    }
    
    private static String toString(Calendar date)
    {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy.MM.dd-hh:mm:ss.S a z");
        formatter.setTimeZone(date.getTimeZone());
        return formatter.format(date.getTime());
    }
    
    private void assertSequence(Calendar midnight, Object[][] expected)
    {
        Calendar nextEvent = midnight;
        
        for (int i = 0; i < expected.length; i++)
        {
            long lastMs = nextEvent.getTimeInMillis();
            nextEvent = RolloverFileOutputStream.toMidnight(nextEvent);
            assertThat("Next Event", toString(nextEvent), is(expected[i][0]));
            long duration = (nextEvent.getTimeInMillis() - lastMs);
            assertThat("Duration to next event", duration, is((long) expected[i][1]));
        }
    }
    
    @Test
    public void testMidnightRolloverCalc_PST_DST_Start() throws ParseException
    {
        TimeZone zone = toZoneId("PST");
        Calendar initialDate = toDateTime("2016.03.10-01:23:45.0 PM PST", zone);
        
        Calendar midnight = RolloverFileOutputStream.toMidnight(initialDate);
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
    public void testMidnightRolloverCalc_PST_DST_End() throws ParseException
    {
        TimeZone zone = toZoneId("PST");
        Calendar initialDate = toDateTime("2016.11.03-11:22:33.0 AM PDT", zone);
    
        Calendar midnight = RolloverFileOutputStream.toMidnight(initialDate);
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
    public void testMidnightRolloverCalc_Sydney_DST_Start() throws ParseException
    {
        TimeZone zone = toZoneId("Australia/Sydney");
        Calendar initialDate = toDateTime("2016.09.30-01:23:45.0 PM AEST", zone);
    
        Calendar midnight = RolloverFileOutputStream.toMidnight(initialDate);
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
    public void testMidnightRolloverCalc_Sydney_DST_End() throws ParseException
    {
        TimeZone zone = toZoneId("Australia/Sydney");
        Calendar initialDate = toDateTime("2016.04.01-11:22:33.0 AM AEDT", zone);
    
        Calendar midnight = RolloverFileOutputStream.toMidnight(initialDate);
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
    
    @Test
    public void testFileHandling() throws Exception
    {
        File testDir = testingDir.getEmptyPathDir().toFile();
        Path testPath = testDir.toPath();
        FS.ensureEmpty(testDir);
    
        TimeZone zone = toZoneId("Australia/Sydney");
        Calendar now = toDateTime("2016.04.10-08:30:12.3 AM AEST", zone);
        
        File template = new File(testDir,"test-rofos-yyyy_mm_dd.log");

        try (RolloverFileOutputStream rofos = 
            new RolloverFileOutputStream(template.getAbsolutePath(),false,3,zone,null,null,now))
        {
            rofos.write("TICK".getBytes());
            rofos.flush();
        }
        
        now.add(Calendar.MINUTE, 5);
        
        try (RolloverFileOutputStream rofos = 
            new RolloverFileOutputStream(template.getAbsolutePath(),false,3,zone,null,null,now))
        {
            rofos.write("TOCK".getBytes());
            rofos.flush();
            String[] ls = testDir.list();
            assertThat(ls.length,is(2));
            String backup = null;
            for (String n: ls)
            {
                if (!"test-rofos-2016_04_10.log".equals(n))
                    backup = n;
            }
            
            assertThat(Arrays.asList(ls),Matchers.containsInAnyOrder(backup,"test-rofos-2016_04_10.log"));
            
            Files.setLastModifiedTime(testPath.resolve(backup),FileTime.fromMillis(now.getTimeInMillis()));
            Files.setLastModifiedTime(testPath.resolve("test-rofos-2016_04_10.log"),FileTime.fromMillis(now.getTimeInMillis()));

            // Copy calendar (don't want to change "now")
            Calendar time = Calendar.getInstance();
            time.setTimeZone(now.getTimeZone());
            time.setTime(now.getTime());
            time.add(Calendar.DAY_OF_MONTH, -1);
            
            for (int i=10;i-->5;)
            {
                String file = "test-rofos-2016_04_0"+i+".log";
                Path path = testPath.resolve(file);
                FS.touch(path);
                Files.setLastModifiedTime(path,FileTime.fromMillis(time.getTimeInMillis()));
                
                if (i%2==0)
                {
                    file = "test-rofos-2016_04_0"+i+".log.083512300";
                    path = testPath.resolve(file);
                    FS.touch(path);
                    Files.setLastModifiedTime(path,FileTime.fromMillis(time.getTimeInMillis()));
                    time.add(Calendar.DAY_OF_MONTH, -1);
                }

                file = "unrelated-"+i;
                path = testPath.resolve(file);
                FS.touch(path);
                Files.setLastModifiedTime(path,FileTime.fromMillis(time.getTimeInMillis()));
    
                time.add(Calendar.DAY_OF_MONTH, -1);
            }

            ls = testDir.list();
            assertThat(ls.length,is(14));
            assertThat(Arrays.asList(ls),Matchers.containsInAnyOrder(
                "test-rofos-2016_04_05.log",
                "test-rofos-2016_04_06.log",
                "test-rofos-2016_04_07.log", 
                "test-rofos-2016_04_08.log", 
                "test-rofos-2016_04_09.log",
                "test-rofos-2016_04_10.log",
                "test-rofos-2016_04_06.log.083512300",
                "test-rofos-2016_04_08.log.083512300",
                "test-rofos-2016_04_10.log.083512003",
                "unrelated-9",
                "unrelated-8",
                "unrelated-7",
                "unrelated-6",
                "unrelated-5"
                ));

            rofos.removeOldFiles(now);
            ls = testDir.list();
            assertThat(ls.length,is(10));
            assertThat(Arrays.asList(ls),Matchers.containsInAnyOrder(
                "test-rofos-2016_04_08.log", 
                "test-rofos-2016_04_09.log",
                "test-rofos-2016_04_10.log",
                "test-rofos-2016_04_08.log.083512300", 
                "test-rofos-2016_04_10.log.083512003",
                "unrelated-9",
                "unrelated-8",
                "unrelated-7",
                "unrelated-6",
                "unrelated-5"));
            

            assertThat(IO.toString(new FileReader(new File(testDir,backup))),is("TICK"));
            assertThat(IO.toString(new FileReader(new File(testDir,"test-rofos-2016_04_10.log"))),is("TOCK"));
            
        }
    }

    @Test
    public void testRollover() throws Exception
    {
        File testDir = testingDir.getEmptyPathDir().toFile();
        FS.ensureEmpty(testDir);
    
        TimeZone zone = toZoneId("Australia/Sydney");
        Calendar now = toDateTime("2016.04.10-11:59:58.0 PM AEST", zone);
        
        File template = new File(testDir,"test-rofos-yyyy_mm_dd.log");
        
        try (RolloverFileOutputStream rofos = 
            new RolloverFileOutputStream(template.getAbsolutePath(),false,0,zone,null,null,now))
        {
            rofos.write("BEFORE".getBytes());
            rofos.flush();
            String[] ls = testDir.list();
            assertThat(ls.length,is(1));
            assertThat(ls[0],is("test-rofos-2016_04_10.log"));

            TimeUnit.SECONDS.sleep(5);
            rofos.write("AFTER".getBytes());
            ls = testDir.list();
            assertThat(ls.length,is(2));
            
            for (String n : ls)
            {
                String content = IO.toString(new FileReader(new File(testDir,n)));
                if ("test-rofos-2016_04_10.log".equals(n))
                {
                    assertThat(content,is("BEFORE"));
                }
                else
                {
                    assertThat(content,is("AFTER"));
                }
            }
        }
    }
}
