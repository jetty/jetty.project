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

package org.eclipse.jetty.http.spi;

import java.net.InetSocketAddress;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;

class JettyHttpsParameters extends HttpsParameters 
{
  final InetSocketAddress addr;
  final HttpsConfigurator cfg;
  SslContextFactory.Server factoryServer = new SslContextFactory.Server();

  JettyHttpsParameters(HttpsConfigurator cfg, InetSocketAddress addr) 
  {
    this.addr = addr;
    this.cfg = cfg;
    factoryServer.setSslContext(cfg.getSSLContext());
  }

  @Override
  public InetSocketAddress getClientAddress() 
  {
    return addr;
  }

  @Override
  public HttpsConfigurator getHttpsConfigurator() 
  {
    return cfg;
  }

  SSLParameters params;

  @Override
  public void setSSLParameters(SSLParameters p) 
  {
    params = p;
    factoryServer.setWantClientAuth(p.getWantClientAuth());
    factoryServer.setNeedClientAuth(p.getNeedClientAuth());
  }

  Server getSSLFactoryServer() 
  {
    return factoryServer;
  }
}
