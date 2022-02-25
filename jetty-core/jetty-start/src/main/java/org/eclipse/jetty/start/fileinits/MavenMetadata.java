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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.start.StartLog;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Simple parser for maven-metadata.xml files
 */
public class MavenMetadata
{
    private String groupId;
    private String artifactId;
    private String version;

    private String lastUpdated;
    private String snapshotTimestamp;
    private String snapshotBuildNumber;

    private final Map<String, Snapshot> snapshots = new HashMap<>();

    public MavenMetadata(Path metadataXml)
        throws IOException, ParserConfigurationException, SAXException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream input = Files.newInputStream(metadataXml))
        {
            Document doc = builder.parse(input);
            parseRoot(doc.getDocumentElement(), metadataXml);
        }
    }

    public String getGroupId()
    {
        return groupId;
    }

    public String getArtifactId()
    {
        return artifactId;
    }

    public String getVersion()
    {
        return version;
    }

    public String getLastUpdated()
    {
        return lastUpdated;
    }

    public String getSnapshotTimestamp()
    {
        return snapshotTimestamp;
    }

    public String getSnapshotBuildNumber()
    {
        return snapshotBuildNumber;
    }

    public Collection<Snapshot> getSnapshots()
    {
        return snapshots.values();
    }

    public Snapshot getSnapshot(String classifier, String extension)
    {
        Snapshot snapshot = snapshots.get(asSuffix(classifier, extension));
        if (snapshot == null)
        {
            snapshot = new Snapshot();
            snapshot.value = String.format("%s-%s-%s",
                getVersion().replaceFirst("-SNAPSHOT$", ""),
                snapshotTimestamp, snapshotBuildNumber);
        }
        return snapshot;
    }

    /**
     * Tests the current time against the provided timestamp.
     * <p>
     * If the current time is the next day from the provided timestamp,
     * it is considered expired.
     * </p>
     *
     * @param lastUpdated the time representing the last update
     * @return true if it's the next day from the timestamp (or later)
     */
    public static boolean isExpiredTimestamp(String lastUpdated)
    {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(utc);

        try
        {
            LocalDate lastUpdatedDate = LocalDate.parse(lastUpdated, formatter).atStartOfDay().toLocalDate();
            return today.isAfter(lastUpdatedDate);
        }
        catch (DateTimeParseException e)
        {
            StartLog.debug(e);
            return false;
        }
    }

    private static String asSuffix(String classifier, String type)
    {
        if (classifier != null)
            return String.format("-%s.%s", classifier, type);
        else
            return String.format(".%s", type);
    }

    private List<Node> listOf(NodeList nodeList)
    {
        List<Node> children = new ArrayList<>();
        int length = nodeList.getLength();
        for (int i = 0; i < length; i++)
        {
            children.add(nodeList.item(i));
        }
        return children;
    }

    private void parseRoot(Element root, Path metadataXml) throws IOException
    {
        if (!root.getNodeName().equals("metadata"))
        {
            throw new IOException("Unrecognized maven-metadata.xml <" + root.getNodeName() + ">: " + metadataXml);
        }

        listOf(root.getChildNodes()).stream()
            .filter((node) -> node.getNodeType() == Node.ELEMENT_NODE)
            .map((node) -> (Element)node)
            .forEach((elem) ->
            {
                switch (elem.getNodeName())
                {
                    case "groupId":
                        this.groupId = elem.getTextContent();
                        break;
                    case "artifactId":
                        this.artifactId = elem.getTextContent();
                        break;
                    case "version":
                        this.version = elem.getTextContent();
                        break;
                    case "versioning":
                        parseVersioning(elem, metadataXml);
                        break;
                }
            });
    }

    private void parseVersioning(Element versioning, Path metadataXml)
    {
        listOf(versioning.getChildNodes()).stream()
            .filter((node) -> node.getNodeType() == Node.ELEMENT_NODE)
            .map((node) -> (Element)node)
            .forEach((elem) ->
            {
                switch (elem.getNodeName())
                {
                    case "snapshot":
                        parseSnapshot(elem);
                        break;
                    case "lastUpdated":
                        this.lastUpdated = elem.getTextContent();
                        break;
                    case "snapshotVersions":
                        parseSnapshotVersions(elem);
                        break;
                }
            });
    }

    private void parseSnapshot(Element snapshot)
    {
        listOf(snapshot.getChildNodes()).stream()
            .filter((node) -> node.getNodeType() == Node.ELEMENT_NODE)
            .map((node) -> (Element)node)
            .forEach((elem) ->
            {
                switch (elem.getNodeName())
                {
                    case "timestamp":
                        this.snapshotTimestamp = elem.getTextContent();
                        break;
                    case "buildNumber":
                        this.snapshotBuildNumber = elem.getTextContent();
                        break;
                }
            });
    }

    private void parseSnapshotVersions(Element snapshotVersions)
    {
        listOf(snapshotVersions.getChildNodes()).stream()
            .filter((node) -> node.getNodeType() == Node.ELEMENT_NODE)
            .map((node) -> (Element)node)
            .forEach((elem) ->
            {
                if ("snapshotVersion".equals(elem.getNodeName()))
                {
                    parseSnapshotVersion(elem);
                }
            });
    }

    private void parseSnapshotVersion(Element snapshotVersion)
    {
        Snapshot snapshot = new Snapshot();
        listOf(snapshotVersion.getChildNodes()).stream()
            .filter((node) -> node.getNodeType() == Node.ELEMENT_NODE)
            .map((node) -> (Element)node)
            .forEach((elem) ->
            {
                switch (elem.getNodeName())
                {
                    case "classifier":
                        snapshot.classifier = elem.getTextContent();
                        break;
                    case "extension":
                        snapshot.extension = elem.getTextContent();
                        break;
                    case "value":
                        snapshot.value = elem.getTextContent();
                        break;
                    case "updated":
                        snapshot.updated = elem.getTextContent();
                        break;
                }
            });
        snapshots.put(snapshot.toSuffix(), snapshot);
    }

    public static class Snapshot
    {
        String classifier;
        String extension;
        String value;
        String updated;

        private String toSuffix()
        {
            return asSuffix(classifier, extension);
        }

        public String getClassifier()
        {
            return classifier;
        }

        public String getExtension()
        {
            return extension;
        }

        public String getUpdated()
        {
            return updated;
        }

        public String getValue()
        {
            return value;
        }
    }
}
