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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MavenMetadataTest
{
    @Test
    public void testParseExample() throws ParserConfigurationException, SAXException, IOException
    {
        Path example = MavenTestingUtils.getTestResourcePathFile("example-maven-metadata.xml");
        MavenMetadata mavenMetadata = new MavenMetadata(example);

        assertThat("Metadata.groupId", mavenMetadata.getGroupId(), is("org.eclipse.jetty"));
        assertThat("Metadata.artifactId", mavenMetadata.getArtifactId(), is("jetty-rewrite"));
        assertThat("Metadata.version", mavenMetadata.getVersion(), is("10.0.0-SNAPSHOT"));

        assertThat("Metadata.versioning.lastUpdated", mavenMetadata.getLastUpdated(), is("20200918022411"));

        assertThat("Metadata.versioning.snapshot.timestamp", mavenMetadata.getSnapshotTimestamp(), is("20200918.022411"));
        assertThat("Metadata.versioning.snapshot.buildNumber", mavenMetadata.getSnapshotBuildNumber(), is("580"));

        assertThat("Metadata.snapshots.size", mavenMetadata.getSnapshots().size(), is(4));

        assertThat("Metadata.snapshot(null, 'jar').value",
            mavenMetadata.getSnapshot(null, "jar").getValue(),
            is("10.0.0-20200918.022411-580"));
    }

    @Test
    public void testIsExpiredTimestampVeryOld()
    {
        String timestamp = "20190822223344";
        assertTrue(MavenMetadata.isExpiredTimestamp(timestamp), "Timestamp should be stale: " + timestamp);
    }

    @Test
    public void testIsExpiredTimestampNextWeek()
    {
        LocalDateTime nextWeek = LocalDateTime.now().plusWeeks(1);
        String timestamp = getTimestampFormatter().format(nextWeek);
        assertFalse(MavenMetadata.isExpiredTimestamp(timestamp), "Timestamp should NOT be stale: " + timestamp);
    }

    @Test
    public void testIsExpiredTimestampNow()
    {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        String timestamp = getTimestampFormatter().format(now);
        assertFalse(MavenMetadata.isExpiredTimestamp(timestamp), "Timestamp should NOT be stale: " + timestamp);
    }

    @Test
    public void testIsExpiredTimestampYesterday()
    {
        LocalDateTime yesterday = LocalDateTime.now(ZoneId.of("UTC")).minusDays(1);
        String timestamp = getTimestampFormatter().format(yesterday);
        assertTrue(MavenMetadata.isExpiredTimestamp(timestamp), "Timestamp should be stale: " + timestamp);
    }

    private DateTimeFormatter getTimestampFormatter()
    {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"));
    }
}