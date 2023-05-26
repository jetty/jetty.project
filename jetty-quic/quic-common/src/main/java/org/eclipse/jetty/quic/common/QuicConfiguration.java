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

package org.eclipse.jetty.quic.common;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>A record that captures QUIC configuration parameters.</p>
 */
public class QuicConfiguration
{
    public static final String CONTEXT_KEY = QuicConfiguration.class.getName();

    private List<String> protocols = List.of();
    private boolean disableActiveMigration;
    private int maxBidirectionalRemoteStreams;
    private int maxUnidirectionalRemoteStreams;
    private int sessionRecvWindow;
    private int bidirectionalStreamRecvWindow;
    private int unidirectionalStreamRecvWindow;
    private Path pemWorkDirectory;
    private final Map<String, Object> implementationConfiguration = new HashMap<>();

    public List<String> getProtocols()
    {
        return protocols;
    }

    public void setProtocols(List<String> protocols)
    {
        this.protocols = protocols;
    }

    public boolean isDisableActiveMigration()
    {
        return disableActiveMigration;
    }

    public void setDisableActiveMigration(boolean disableActiveMigration)
    {
        this.disableActiveMigration = disableActiveMigration;
    }

    public int getMaxBidirectionalRemoteStreams()
    {
        return maxBidirectionalRemoteStreams;
    }

    public void setMaxBidirectionalRemoteStreams(int maxBidirectionalRemoteStreams)
    {
        this.maxBidirectionalRemoteStreams = maxBidirectionalRemoteStreams;
    }

    public int getMaxUnidirectionalRemoteStreams()
    {
        return maxUnidirectionalRemoteStreams;
    }

    public void setMaxUnidirectionalRemoteStreams(int maxUnidirectionalRemoteStreams)
    {
        this.maxUnidirectionalRemoteStreams = maxUnidirectionalRemoteStreams;
    }

    public int getSessionRecvWindow()
    {
        return sessionRecvWindow;
    }

    public void setSessionRecvWindow(int sessionRecvWindow)
    {
        this.sessionRecvWindow = sessionRecvWindow;
    }

    public int getBidirectionalStreamRecvWindow()
    {
        return bidirectionalStreamRecvWindow;
    }

    public void setBidirectionalStreamRecvWindow(int bidirectionalStreamRecvWindow)
    {
        this.bidirectionalStreamRecvWindow = bidirectionalStreamRecvWindow;
    }

    public int getUnidirectionalStreamRecvWindow()
    {
        return unidirectionalStreamRecvWindow;
    }

    public void setUnidirectionalStreamRecvWindow(int unidirectionalStreamRecvWindow)
    {
        this.unidirectionalStreamRecvWindow = unidirectionalStreamRecvWindow;
    }

    public Path getPemWorkDirectory()
    {
        return pemWorkDirectory;
    }

    public void setPemWorkDirectory(Path pemWorkDirectory)
    {
        this.pemWorkDirectory = pemWorkDirectory;
    }

    public Map<String, Object> getImplementationConfiguration()
    {
        return implementationConfiguration;
    }
}
