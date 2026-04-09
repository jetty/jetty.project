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

package org.eclipse.jetty.ee11.servlets;

/**
 * Header Filter
 * <p>
 * This filter sets or adds a header to the response.
 * <p>
 * The {@code headerConfig} init param is a CSV of actions to perform on headers, with the following syntax: <br>
 * [action] [header name]: [header value] <br>
 * [action] can be one of <code>set</code>, <code>add</code>, <code>setDate</code>, or <code>addDate</code> <br>
 * The date actions will add the header value in milliseconds to the current system time before setting a date header.
 * <p>
 * Below is an example value for <code>headerConfig</code>:<br>
 *
 * <pre>
 * set X-Frame-Options: DENY,
 * "add Cache-Control: no-cache, no-store, must-revalidate",
 * setDate Expires: 31540000000,
 * addDate Date: 0
 * </pre>
 *
 * @see IncludeExcludeBasedFilter
 */
public class HeaderFilter extends org.eclipse.jetty.ee.servlets.HeaderFilter
{
}
