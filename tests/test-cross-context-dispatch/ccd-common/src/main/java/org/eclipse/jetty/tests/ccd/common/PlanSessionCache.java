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

package org.eclipse.jetty.tests.ccd.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.NullSessionDataStore;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlanSessionCache extends DefaultSessionCache
{
    private static final Logger LOG = LoggerFactory.getLogger(PlanSessionCache.class);
    private final Path outputFile;

    public PlanSessionCache(SessionManager manager)
    {
        super(manager);
        outputFile = Path.of(System.getProperty("jetty.base"), "work/session.log");
        LOG.info("outputFile={}", outputFile);
        setSessionDataStore(new NullSessionDataStore());
    }

    @Override
    public ManagedSession newSession(SessionData data)
    {
        logEvent("newSession()", data);
        return super.newSession(data);
    }

    @Override
    public void commit(ManagedSession session) throws Exception
    {
        logEvent("commit()", session);
        super.commit(session);
    }

    @Override
    public void release(ManagedSession session) throws Exception
    {
        logEvent("release()", session);
        super.release(session);
    }

    private void logEvent(String eventType, SessionData data)
    {
        String name = "SessionCache.event." + eventType;
        String value = "";
        if (data != null)
        {
            value = String.format("id=%s|contextPath=%s", data.getId(), data.getContextPath());
        }
        logAttribute(name, value);
    }

    private void logEvent(String eventType, ManagedSession session)
    {
        String name = "SessionCache.event." + eventType;
        String value = "";
        if (session != null)
        {
            value = String.format("id=%s", session.getId());
            SessionData data = session.getSessionData();
            if (data != null)
            {
                value = String.format("id=%s|contextPath=%s", data.getId(), data.getContextPath());
            }
        }
        logAttribute(name, value);
    }

    private void logAttribute(String name, String value)
    {
        String line = name + "=" + value;
        if (LOG.isInfoEnabled())
            LOG.info(line);

        try
        {
            Files.writeString(outputFile, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            LOG.warn("Unable to write to " + outputFile, e);
        }
    }
}
