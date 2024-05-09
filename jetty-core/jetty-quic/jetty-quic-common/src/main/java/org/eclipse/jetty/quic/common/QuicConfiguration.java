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
import org.eclipse.jetty.util.component.ContainerLifeCycle;

/**
 * <p>A record that captures QUIC configuration parameters.</p>
 */
public class QuicConfiguration extends ContainerLifeCycle
{
    public static final String CONTEXT_KEY = QuicConfiguration.class.getName();
    public static final String PRIVATE_KEY_PEM_PATH_KEY = CONTEXT_KEY + ".privateKeyPemPath";
    public static final String CERTIFICATE_CHAIN_PEM_PATH_KEY = CONTEXT_KEY + ".certificateChainPemPath";
    public static final String TRUSTED_CERTIFICATES_PEM_PATH_KEY = CONTEXT_KEY + ".trustedCertificatesPemPath";

    private int inputBufferSize = 2048;
    private int outputBufferSize = 2048;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;
    private List<String> protocols = List.of();
    private boolean disableActiveMigration;
    private int maxBidirectionalRemoteStreams;
    private int maxUnidirectionalRemoteStreams;
    private int sessionRecvWindow;
    private int bidirectionalStreamRecvWindow;
    private int unidirectionalStreamRecvWindow;
    private Path pemWorkDirectory;
    private final Map<String, Object> implementationConfiguration = new HashMap<>();

    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

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
        if (isStarted())
            throw new IllegalStateException("cannot change PEM working directory after start");
        this.pemWorkDirectory = pemWorkDirectory;
    }

    public Map<String, Object> getImplementationConfiguration()
    {
        return implementationConfiguration;
    }
}
