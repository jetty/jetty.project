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
import groovy.xml.XmlParser

def rootNode = new XmlParser().parse(new File( basedir, 'webapp-war/target/effective-web.xml'))
// find context-param node with param-name == org.eclipse.jetty.resources
def ctxParam = rootNode.'**'.find{it.text() == "org.eclipse.jetty.resources"}.parent()
def paramValue = ctxParam.'param-value'.get(0).text().trim()
// assert the value of param-value child node
assert paramValue.contains('${user.dir.uri}/resources/target/classes/META-INF/resources')

