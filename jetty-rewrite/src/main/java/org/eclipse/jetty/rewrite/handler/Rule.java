//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;

/**
 * An abstract rule for creating rewrite rules.
 */
public abstract class Rule
{
    /**
     * Interface used to apply a changed target if {@link RuleContainer#setRewriteRequestURI(boolean)} is true.
     */
    public interface ApplyURI
    {
        void applyURI(Request request, String oldURI, String newURI) throws IOException;
    }

    protected boolean _terminating;
    protected boolean _handling;

    /**
     * This method calls tests the rule against the request/response pair and if the Rule
     * applies, then the rule's action is triggered.
     *
     * @param target The target of the request
     * @param request the request
     * @param response the response
     * @return The new target if the rule has matched, else null
     * @throws IOException if unable to match the rule
     */
    public abstract String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException;

    /**
     * Sets terminating to true or false.
     *
     * @param terminating If true, this rule will terminate the loop if this rule has been applied.
     */
    public void setTerminating(boolean terminating)
    {
        _terminating = terminating;
    }

    /**
     * Returns the terminating flag value.
     *
     * @return <code>true</code> if the rule needs to terminate; <code>false</code> otherwise.
     */
    public boolean isTerminating()
    {
        return _terminating;
    }

    /**
     * Returns the handling flag value.
     *
     * @return <code>true</code> if the rule handles the request and nested handlers should not be called.
     */
    public boolean isHandling()
    {
        return _handling;
    }

    /**
     * Set the handling flag value.
     *
     * @param handling true if the rule handles the request and nested handlers should not be called.
     */
    public void setHandling(boolean handling)
    {
        _handling = handling;
    }

    /**
     * Returns the handling and terminating flag values.
     */
    @Override
    public String toString()
    {
        return this.getClass().getName() + (_handling ? "[H" : "[h") + (_terminating ? "T]" : "t]");
    }
}
