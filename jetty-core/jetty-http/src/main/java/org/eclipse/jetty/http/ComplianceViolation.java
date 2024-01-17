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
import java.util.Objects;
import java.util.Set;

import org.eclipse.jetty.util.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Compliance Violation represents a requirement of an RFC, specification or Jetty implementation
 * that may be allowed to be violated if it is included in a {@link ComplianceViolation.Mode}.
 * For example, supporting HTTP/0.9 is no longer a requirement of the current HTTP RFC, so by including
 * the {@link HttpCompliance.Violation#HTTP_0_9} in the {@link HttpCompliance} {@link ComplianceViolation.Mode}
 * is interpreted as allowing HTTP/0.9 to be supported.
 */
public interface ComplianceViolation
{
    /**
     * @return The name of the violation.
     */
    String getName();

    /**
     * @return A URL to the specification that provides more information regarding the requirement that may be violated.
     */
    String getURL();

    /**
     * @return A short description of the violation.
     */
    String getDescription();

    /**
     * @param mode A {@link ComplianceViolation.Mode} to test against
     * @return True iff this violations is allowed by the mode.
     */
    default boolean isAllowedBy(Mode mode)
    {
        return mode.allows(this);
    }

    /**
     * A Mode is a set of {@link ComplianceViolation}s that are allowed.
     */
    interface Mode
    {
        /**
         * @return The name of the compliance violation mode.
         */
        String getName();

        /**
         * @param violation The {@link ComplianceViolation} to test
         * @return true iff the violation is allowed by this mode.
         */
        boolean allows(ComplianceViolation violation);

        /**
         * @return The immutable set of all known violations for this mode.
         */
        Set<? extends ComplianceViolation> getKnown();

        /**
         * @return The immutable set of violations allowed by this mode.
         */
        Set<? extends ComplianceViolation> getAllowed();
    }

    public static record Event(ComplianceViolation.Mode mode, ComplianceViolation violation, String details)
    {
        @Override
        public String toString()
        {
            return String.format("%s (see %s) in mode %s for %s",
                violation.getDescription(), violation.getURL(), mode, details);
        }
    };

    /**
     * A listener that can be notified of violations.
     */
    interface Listener
    {
        /**
         * A new Request has begun.
         *
         * @param request the request attributes, or null if the Request does not exist yet (eg: during parsing of HTTP/1.1 headers, before request is created)
         */
        default void onRequestBegin(Attributes request)
        {
        }

        /**
         * A Request has ended.
         *
         * @param request the request attribtues, or null if Request does not exist yet (eg: during handling of a {@link BadMessageException})
         */
        default void onRequestEnd(Attributes request)
        {
        }

        /**
         * The compliance violation event.
         *
         * @param event the compliance violation event
         */
        default void onComplianceViolation(Event event)
        {
            onComplianceViolation(event.mode, event.violation, event.details);
        }

        /**
         * The compliance violation event.
         *
         * @param mode the mode
         * @param violation the violation
         * @param details the details
         * @deprecated use {@link #onComplianceViolation(Event)} instead.  Will be removed in Jetty 12.1.0
         */
        @Deprecated(since = "12.0.5", forRemoval = true)
        default void onComplianceViolation(Mode mode, ComplianceViolation violation, String details)
        {
        }
    }

    /**
     * A Listener that represents multiple user {@link ComplianceViolation.Listener} instances
     */
    class ListenerCollection implements Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ListenerCollection.class);
        private final List<ComplianceViolation.Listener> userListeners;

        /**
         * Construct a new ComplianceViolations that will notify user listeners.
         * @param userListeners the user listeners to notify, null or empty is allowed.
         */
        public ListenerCollection(List<ComplianceViolation.Listener> userListeners)
        {
            Objects.requireNonNull(userListeners);
            if (userListeners.isEmpty())
                throw new IllegalStateException("Listener list is empty");
            this.userListeners =  userListeners;
        }

        @Override
        public void onRequestBegin(Attributes request)
        {
            for (ComplianceViolation.Listener listener : userListeners)
            {
                try
                {
                    listener.onRequestBegin(request);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to notify {}.onRequestBegin({})", listener.getClass().getName(), request, e);
                }
            }

        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            for (ComplianceViolation.Listener listener : userListeners)
            {
                try
                {
                    listener.onRequestEnd(request);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to notify {}.onRequestEnd({})", listener.getClass().getName(), request, e);
                }
            }
        }

        /**
         * Get a specific ComplianceViolation.Listener from collected user listeners
         * @param clazz the class to look for
         * @return the instance of the class in the user listeners
         * @param <T> the type of class
         */
        public <T> T getUserListener(Class<T> clazz)
        {
            for (ComplianceViolation.Listener listener : userListeners)
            {
                if (clazz.isInstance(listener))
                    return clazz.cast(listener);
            }
            return null;
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            assert event != null;
            for (Listener listener : userListeners)
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

    interface ListenerFactory
    {
        Listener newComplianceViolationListener();
    }

    public class LoggingListener implements Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ComplianceViolation.class);

        @Override
        public void onComplianceViolation(Event event)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(event.toString());
        }
    }

    public class CapturingListenerFactory implements ListenerFactory
    {
        @Override
        public Listener newComplianceViolationListener()
        {
            return new CapturingListener();
        }
    }

    public class CapturingListener implements Listener
    {
        public static final String VIOLATIONS_ATTR_KEY = "org.eclipse.jetty.http.compliance.violations";
        private List<Event> events = new ArrayList<>();

        @Override
        public void onRequestBegin(Attributes request)
        {
            if (request != null)
                request.setAttribute(VIOLATIONS_ATTR_KEY, events);
        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            events = new ArrayList<>();
        }

        @Override
        public void onComplianceViolation(Event event)
        {
            events.add(event);
        }
    }
}
