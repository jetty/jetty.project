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

package org.eclipse.jetty.http;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A representation of ComplianceViolation.Listeners events that have occurred.
 */
public class ComplianceViolations implements ComplianceViolation.Listener
{
    public static final String VIOLATIONS_ATTR = ComplianceViolations.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger(ComplianceViolations.class);
    private final List<ComplianceViolation.Listener> userListeners;
    private List<ComplianceViolation.Event> events;
    private Attributes attributes;

    /**
     * Construct a new ComplianceViolations that will notify user listeners.
     * @param userListeners the user listeners to notify, null or empty is allowed.
     */
    public ComplianceViolations(List<ComplianceViolation.Listener> userListeners)
    {
        this.userListeners = userListeners;
    }

    @Override
    public void onComplianceViolation(ComplianceViolation.Event event)
    {
        assert event != null;

        notifyUserListeners(event);
        addEvent(event);
        if (LOG.isDebugEnabled())
            LOG.debug(event.toString());
    }

    /**
     * Clear out the tracked {@link Attributes} object and events;
     */
    public void recycle()
    {
        this.events = null;
        this.attributes = null;
    }

    /**
     * Start tracking an {@link Attributes} implementation to store the events in.
     * @param attributes the attributes object to store the event list in.  Stored in key name {@link #VIOLATIONS_ATTR}
     */
    public void setAttribute(Attributes attributes)
    {
        this.attributes = attributes;
        this.attributes.setAttribute(VIOLATIONS_ATTR, events);
    }

    /**
     * The events that have been recorded.
     *
     * @return the list of Events recorded.
     */
    public List<ComplianceViolation.Event> getEvents()
    {
        return events;
    }

    private void addEvent(ComplianceViolation.Event event)
    {
        if (this.events == null)
        {
            this.events = new ArrayList<>();
            if (this.attributes != null)
                setAttribute(this.attributes);
        }
        this.events.add(event);
    }

    private void notifyUserListeners(ComplianceViolation.Event event)
    {
        if (userListeners == null || userListeners.isEmpty())
            return;

        for (ComplianceViolation.Listener listener : userListeners)
        {
            try
            {
                listener.onComplianceViolation(event);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to notify ComplianceViolation.Listener implementation at {} of event {}", listener, event, e);
            }
        }
    }
}
