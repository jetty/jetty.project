//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.quiche;

import org.eclipse.jetty.quic.quiche.ffi.LibQuiche;

public class QuicheConfig
{
    static
    {
        LibQuiche.Logging.enable();
    }

    public enum CongestionControl
    {
        RENO(LibQuiche.quiche_cc_algorithm.QUICHE_CC_RENO),
        CUBIC(LibQuiche.quiche_cc_algorithm.QUICHE_CC_CUBIC);

        private final int value;
        CongestionControl(int value)
        {
            this.value = value;
        }

        public int getValue()
        {
            return value;
        }
    }

    private int version = LibQuiche.QUICHE_PROTOCOL_VERSION;
    private Boolean verifyPeer;
    private String certChainPemPath;
    private String privKeyPemPath;
    private String[] applicationProtos;
    private CongestionControl congestionControl;
    private Long maxIdleTimeout;
    private Long initialMaxData;
    private Long initialMaxStreamDataBidiLocal;
    private Long initialMaxStreamDataBidiRemote;
    private Long initialMaxStreamDataUni;
    private Long initialMaxStreamsBidi;
    private Long initialMaxStreamsUni;
    private Boolean disableActiveMigration;

    public QuicheConfig()
    {
    }

    public int getVersion()
    {
        return version;
    }

    public Boolean getVerifyPeer()
    {
        return verifyPeer;
    }

    public String getCertChainPemPath()
    {
        return certChainPemPath;
    }

    public String getPrivKeyPemPath()
    {
        return privKeyPemPath;
    }

    public String[] getApplicationProtos()
    {
        return applicationProtos;
    }

    public CongestionControl getCongestionControl()
    {
        return congestionControl;
    }

    public Long getMaxIdleTimeout()
    {
        return maxIdleTimeout;
    }

    public Long getInitialMaxData()
    {
        return initialMaxData;
    }

    public Long getInitialMaxStreamDataBidiLocal()
    {
        return initialMaxStreamDataBidiLocal;
    }

    public Long getInitialMaxStreamDataBidiRemote()
    {
        return initialMaxStreamDataBidiRemote;
    }

    public Long getInitialMaxStreamDataUni()
    {
        return initialMaxStreamDataUni;
    }

    public Long getInitialMaxStreamsBidi()
    {
        return initialMaxStreamsBidi;
    }

    public Long getInitialMaxStreamsUni()
    {
        return initialMaxStreamsUni;
    }

    public Boolean getDisableActiveMigration()
    {
        return disableActiveMigration;
    }

    public void setVersion(int version)
    {
        this.version = version;
    }

    public void setVerifyPeer(Boolean verify)
    {
        this.verifyPeer = verify;
    }

    public void setCertChainPemPath(String path)
    {
        this.certChainPemPath = path;
    }

    public void setPrivKeyPemPath(String path)
    {
        this.privKeyPemPath = path;
    }

    public void setApplicationProtos(String... protos)
    {
        this.applicationProtos = protos;
    }

    public void setCongestionControl(CongestionControl cc)
    {
        this.congestionControl = cc;
    }

    public void setMaxIdleTimeout(Long timeoutInMs)
    {
        this.maxIdleTimeout = timeoutInMs;
    }

    public void setInitialMaxData(Long sizeInBytes)
    {
        this.initialMaxData = sizeInBytes;
    }

    public void setInitialMaxStreamDataBidiLocal(Long sizeInBytes)
    {
        this.initialMaxStreamDataBidiLocal = sizeInBytes;
    }

    public void setInitialMaxStreamDataBidiRemote(Long sizeInBytes)
    {
        this.initialMaxStreamDataBidiRemote = sizeInBytes;
    }

    public void setInitialMaxStreamDataUni(Long sizeInBytes)
    {
        this.initialMaxStreamDataUni = sizeInBytes;
    }

    public void setInitialMaxStreamsBidi(Long sizeInBytes)
    {
        this.initialMaxStreamsBidi = sizeInBytes;
    }

    public void setInitialMaxStreamsUni(Long sizeInBytes)
    {
        this.initialMaxStreamsUni = sizeInBytes;
    }

    public void setDisableActiveMigration(Boolean disable)
    {
        this.disableActiveMigration = disable;
    }

}
