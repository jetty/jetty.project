<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<%@ page contentType="text/plain; charset=UTF-8" %>
Title: JSTL c:url Tests
[c:url value] = <c:url value="/ref.jsp" />
<c:set var="foo" value="ref.jsp;key=value"/>
[c:url param] = <c:url value="${foo}"><c:param name="noframe" value="true"/></c:url>
