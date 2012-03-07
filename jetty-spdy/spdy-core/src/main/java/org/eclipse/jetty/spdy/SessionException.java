/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.SessionStatus;

public class SessionException extends RuntimeException
{
    private final SessionStatus sessionStatus;

    public SessionException(SessionStatus sessionStatus)
    {
        this.sessionStatus = sessionStatus;
    }

    public SessionException(SessionStatus sessionStatus, String message)
    {
        super(message);
        this.sessionStatus = sessionStatus;
    }

    public SessionException(SessionStatus sessionStatus, Throwable cause)
    {
        super(cause);
        this.sessionStatus = sessionStatus;
    }

    public SessionStatus getSessionStatus()
    {
        return sessionStatus;
    }
}
