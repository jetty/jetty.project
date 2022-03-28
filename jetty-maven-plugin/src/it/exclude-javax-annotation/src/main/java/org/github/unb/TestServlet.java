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

package org.github.unb;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

@WebServlet("/test")
public class TestServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/plain");

        try {
            HttpServlet.class.getClassLoader().loadClass("javax.annotation.Nullable");
        } catch (ClassNotFoundException e) {
            response.getWriter().write("class javax.annotation.Nullable not found");
            return;
        }

        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
        List<String> jars = new ArrayList<>();
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            String jar = getJar(url);
            if (jar != null) {
                jars.add(jar);
            }
        }



        Collections.sort(jars);
        String body = jars.stream().map(Object::toString).collect(Collectors.joining("\n", "", "\n"));
        response.getWriter().write(body);
    }

    /**
     * Tries to determine the JAR from a MANIFEST.MF url.
     *
     * @param url the MANIFEST.MF url
     * @return the corresponding jar, or {@code null} if one cannot be determined
     */
    private String getJar(URL url) {
        String result = null;
        String path = url.getPath();
        int index = path.lastIndexOf('!');
        if (index > 0) {
            path = path.substring(0, index);
            index = path.lastIndexOf('/');
            if (index >= 0) {
                path = path.substring(index + 1);
                result = path.length() > 0 ? path : null;
            }
        }
        return result;
    }

}
