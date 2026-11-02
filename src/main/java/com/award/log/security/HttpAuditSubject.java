package com.award.log.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前 HTTP 会话解析操作者，用于运维审计落库。
 */
@Component
public class HttpAuditSubject {

    public String currentOperatorId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return backgroundOperator();
            }
            HttpServletRequest request = attrs.getRequest();
            Object requestUserId = request.getAttribute(RequestUserResolver.REQ_USER_ID);
            if (requestUserId != null) {
                return String.valueOf(requestUserId);
            }
            HttpSession session = request.getSession(false);
            if (session == null) {
                return backgroundOperator();
            }
            Object uid = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
            return uid == null ? backgroundOperator() : String.valueOf(uid);
        } catch (Exception e) {
            return null;
        }
    }

    private String backgroundOperator() {
        var context=OpsSecurityContext.get();
        String requester=context==null?null:context.getRequester();
        return requester!=null&&!"anonymous".equals(requester)&&requester.matches("[A-Za-z0-9:_-]{1,64}")?requester:null;
    }
}
