//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

/**
 * The reference to a specific place in the Spec
 */
public interface SpecReference
{
    /**
     * Request attribute name for storing encountered compliance violations
     */
    String VIOLATIONS_ATTR = "org.eclipse.jetty.spec.violations";

    /**
     * The unique name (to Jetty) for this specific reference
     * @return the unique name for this reference
     */
    String getName();

    /**
     * The URL to the spec (and section, if possible)
     * @return the url to the spec
     */
    String getUrl();

    /**
     * The spec description
     * @return the description of the spec detail that this reference is about
     */
    String getDescription();
}
