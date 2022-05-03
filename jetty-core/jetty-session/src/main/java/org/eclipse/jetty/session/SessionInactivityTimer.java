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

package org.eclipse.jetty.session;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SessionInactivityTimer
 *
 * Each Session has a timer associated with it that fires whenever it has
 * been idle (ie not accessed by a request) for a configurable amount of
 * time, or the Session expires.
 * 
 * The timer is only scheduled when all Requests have exited the Session.
 * If a request enters a Session whose timer is active, it is cancelled.
 */
public class SessionInactivityTimer
{    
    private static final Logger LOG = LoggerFactory.getLogger(SessionInactivityTimer.class);
    
    private final SessionManager _sessionManager;
    private final Scheduler _scheduler;
    private final CyclicTimeout _timer;
    private final Session _session;

    public SessionInactivityTimer(SessionManager sessionManager, Session session, Scheduler scheduler)
    {
        _sessionManager = sessionManager;
        _session = session;
        _scheduler = scheduler;
        _timer = new CyclicTimeout(_scheduler)
        {
            /**
             * The timer on the session went off. This means
             * that the session could either have expired, or
             * it's idle timeout might have been reached.
             */
            @Override
            public void onTimeoutExpired()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Timer expired for session {}", _session.getId());
                long now = System.currentTimeMillis();
                
                try (AutoLock lock = _session.lock())
                {
                    if (_session.getRequests() > 0)
                        return; //session can't expire or be idle if there is a request in it

                    if (LOG.isDebugEnabled())
                        LOG.debug("Inspecting session {}, valid={}", _session.getId(), _session.isValid());

                    if (!_session.isValid())
                        return; //do nothing, session is no longer valid

                    SessionInactivityTimer.this._sessionManager.sessionTimerExpired(_session, now);

                    //TODO is this still needed? If we cancel the timer when a Request arrives
                    //check what happened to the session: if it didn't get evicted and
                    //it hasn't expired, we need to reset the timer
                    if (_session.isResident() && _session.getRequests() <= 0 && _session.isValid() &&
                        !_session.isExpiredAt(now))
                    {
                        //session wasn't expired or evicted, we need to reset the timer
                        SessionInactivityTimer.this.schedule(_session.calculateInactivityTimeout(now));
                    }
                }
            }
        };
    }

    /**
     * @param time the timeout to set; -1 means that the timer will not be
     * scheduled
     */
    public void schedule(long time)
    {
        if (time >= 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("(Re)starting timer for session {} at {}ms", _session.getId(), time);
            _timer.schedule(time, TimeUnit.MILLISECONDS);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not starting timer for session {}", _session.getId());
        }
    }

    public void cancel()
    {
        _timer.cancel();
        if (LOG.isDebugEnabled())
            LOG.debug("Cancelled timer for session {}", _session.getId());
    }

    public void destroy()
    {
        _timer.destroy();
        if (LOG.isDebugEnabled())
            LOG.debug("Destroyed timer for session {}", _session.getId());
    }
}
