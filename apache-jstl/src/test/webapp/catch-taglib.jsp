<%@ page contentType="text/plain; charset=UTF-8" %>
<%@ taglib uri="org.eclipse.jetty.jstl.jtest" prefix="jtest" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
Title: JSTL c:catch test

<jtest:errorhandler>
  <fmt:parseNumber var="parsedNum" value="aaa" />
</jtest:errorhandler>

parsedNum = <c:out value="${parsedNum}"/>