package org.github.unb;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
