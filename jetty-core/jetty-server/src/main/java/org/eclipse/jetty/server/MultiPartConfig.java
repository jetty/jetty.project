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

package org.eclipse.jetty.server;

import java.nio.file.Path;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.MultiPartCompliance;

import static org.eclipse.jetty.http.ComplianceViolation.Listener.NOOP;

public class MultiPartConfig
{
    public static MultiPartConfig from(Request request, Path location, int maxFormKeys, long maxRequestSize, long maxFileSize, long fileSizeThreshold)
    {
        HttpChannel httpChannel = HttpChannel.from(request);
        HttpConfiguration httpConfiguration = request.getConnectionMetaData().getHttpConfiguration();
        MultiPartCompliance multiPartCompliance = httpConfiguration.getMultiPartCompliance();
        ComplianceViolation.Listener complianceViolationListener = httpChannel.getComplianceViolationListener();
        int maxHeaderSize = httpConfiguration.getRequestHeaderSize();

        if (location == null)
            location = request.getContext().getTempDirectory().toPath();

        return new MultiPartConfig(location, maxFormKeys, maxRequestSize,
            maxFileSize, fileSizeThreshold, maxHeaderSize, multiPartCompliance, complianceViolationListener);
    }

    private final Path location;
    private final long fileSizeThreshold;
    private final long maxFileSize;
    private final long maxRequestSize;
    private final int maxFormKeys;
    private final int maxHeadersSize;
    private final MultiPartCompliance compliance;
    private final ComplianceViolation.Listener listener;

    public MultiPartConfig(Path location, int maxFormKeys, long maxRequestSize, long maxFileSize, long fileSizeThreshold)
    {
        this(location, maxFormKeys, maxRequestSize, maxFileSize, fileSizeThreshold, 8 * 1024, MultiPartCompliance.RFC7578, null);
    }

    public MultiPartConfig(Path location, int maxFormKeys, long maxRequestSize, long maxFileSize, long fileSizeThreshold,
                           int maxHeadersSize, MultiPartCompliance compliance, ComplianceViolation.Listener listener)
    {
        this.location = location;
        this.maxFormKeys = maxFormKeys;
        this.maxRequestSize = maxRequestSize;
        this.maxFileSize = maxFileSize;
        this.fileSizeThreshold = fileSizeThreshold;
        this.maxHeadersSize = maxHeadersSize;
        this.compliance = compliance;
        this.listener = listener == null ? NOOP : listener;
    }

    public Path getLocation()
    {
        return location;
    }

    public long getFileSizeThreshold()
    {
        return fileSizeThreshold;
    }

    public long getMaxFileSize()
    {
        return maxFileSize;
    }

    public long getMaxRequestSize()
    {
        return maxRequestSize;
    }

    public int getMaxFormKeys()
    {
        return maxFormKeys;
    }

    public int getMaxHeadersSize()
    {
        return maxHeadersSize;
    }

    public MultiPartCompliance getMultiPartCompliance()
    {
        return compliance;
    }

    public ComplianceViolation.Listener getViolationListener()
    {
        return listener;
    }
}
