// ========================================================================
// Copyright (c) 2010-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.osgi.servletbridge;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.equinox.servletbridge.BridgeServlet;

/**
 * Override the BridgeServlet to report on whether equinox is actually started or not
 * in case it is started asynchroneously.
 * 
 * @author hmalphettes
 */
public class BridgeServletExtended extends BridgeServlet {

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		if (FrameworkLauncherExtended.ASYNCH_START_IN_PROGRESS != null
				&& req.getMethod().equals("GET")) {
			if (FrameworkLauncherExtended.ASYNCH_START_IN_PROGRESS) {
				resp.getWriter().append("Equinox is currently starting...\n");
				return;
			} else if (FrameworkLauncherExtended.ASYNCH_START_FAILURE != null) {
				resp.getWriter().append("Equinox failed to start:\n");
				FrameworkLauncherExtended.ASYNCH_START_FAILURE.printStackTrace(resp.getWriter());
				return;
			}
		}
		super.service(req, resp);
	}
	
}
