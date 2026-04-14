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

package org.eclipse.jetty.ee10.servlet;

import jakarta.servlet.ServletContext;

/**
 * <p>The {@code DefaultServlet}, is a specialization of the {@link ResourceServlet} to be mapped to {@code /} as the "default"
 * servlet for a context.
 * </p>
 * <p>
 * In addition to the servlet init parameters that can be used to configure any {@link ResourceServlet}, the DefaultServlet
 * also looks at {@link ServletContext#getInitParameter(String)} for any parameter starting with {@link #CONTEXT_INIT}, which
 * is then stripped and the resulting name interpreted as a {@link ResourceServlet} init parameter.
 * </p>
 * <p>
 * To serve static content other than as the {@code DefaultServlet} mapped to "/", please use the {@link ResourceServlet} directly.
 * The {@code DefaultServlet} will warn if it is used other than as the default servlet. In future, this may become a fatal error.
 * </p>
 */
public class DefaultServlet extends org.eclipse.jetty.ee.servlet.DefaultServlet
{
}
