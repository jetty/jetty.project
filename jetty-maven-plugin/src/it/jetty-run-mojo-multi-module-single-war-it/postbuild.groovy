/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
File buildLog = new File( basedir, 'build.log' )
assert buildLog.text.contains( 'Started Jetty Server' )

assert buildLog.text.contains( '(1a) >> javax.servlet.ServletContextListener loaded from jar:' )
assert buildLog.text.contains( 'javax/servlet/javax.servlet-api/3.1.0/javax.servlet-api-3.1.0.jar!/javax/servlet/ServletContextListener.class << (1b)' )

assert buildLog.text.contains( '(2a) >> mca.common.CommonService loaded from file:' )
assert buildLog.text.contains( 'common/target/classes/mca/common/CommonService.class << (2b)' )

assert buildLog.text.contains( '(3a) >> mca.module.ModuleApi loaded from file:' )
assert buildLog.text.contains( 'module/module-api/target/classes/mca/module/ModuleApi.class << (3b)' )

assert buildLog.text.contains( '(4a) >> mca.module.ModuleImpl loaded from file:' )
assert buildLog.text.contains( 'module/module-impl/target/classes/mca/module/ModuleImpl.class << (4b)' )

assert buildLog.text.contains( '(5a) >> mca.webapp.WebAppServletListener loaded from file:' )
assert buildLog.text.contains( 'webapp-war/target/classes/mca/webapp/WebAppServletListener.class << (5b)' )
