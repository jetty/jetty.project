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

package org.eclipse.jetty.opentelemetry.server;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class LocalSpanExporter
    implements SpanExporter
{
    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    public LocalSpanExporter()
    {
    }

    public void reset()
    {
        spans.clear();
    }

    public List<SpanData> getSpans(String... namePrefixes)
    {
        if (namePrefixes.length == 0)
            return spans;
        else
        {
            return spans.stream().filter(spanData ->
            {
                for (String name : namePrefixes)
                {
                    if (spanData.getName().startsWith(name))
                        return true;
                }
                return false;
            }).toList();
        }
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans)
    {
        this.spans.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush()
    {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown()
    {
        return CompletableResultCode.ofSuccess();
    }
}
