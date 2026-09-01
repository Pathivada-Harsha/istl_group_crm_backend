package com.istlgroup.istl_group_crm_backend.security;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.istlgroup.istl_group_crm_backend.customException.SessionExpiredException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Binds {@link ActingUserId} / {@link ActingUserRole} / {@link ActingUserName} handler
 * parameters from the authenticated session, via {@link ActingUserService}.
 *
 * <p>Registered in {@code config/WebConfig}. Custom resolvers run before Spring's built-in
 * ones, but that ordering is not load-bearing here: these annotations are ours and no
 * built-in resolver claims them.
 *
 * <p>The declared parameter types mirror what the controllers already used when these were
 * header parameters — most are {@code Long}, a few ({@code NotificationController},
 * {@code TaskController}) declare the id as {@code String} and parse it themselves. Both
 * are supported so no method body has to change.
 */
@Component
@RequiredArgsConstructor
public class ActingUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final ActingUserService actingUserService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingUserId.class)
            || parameter.hasParameterAnnotation(ActingUserRole.class)
            || parameter.hasParameterAnnotation(ActingUserName.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new SessionExpiredException("Your session has expired. Please log in again.");
        }

        if (parameter.hasParameterAnnotation(ActingUserRole.class)) {
            return requireString(parameter, actingUserService.requireRole(request), "@ActingUserRole");
        }
        if (parameter.hasParameterAnnotation(ActingUserName.class)) {
            return requireString(parameter, actingUserService.requireName(request), "@ActingUserName");
        }

        Long userId = actingUserService.requireUserId(request);
        Class<?> type = parameter.getParameterType();
        if (type == Long.class || type == long.class) return userId;
        if (type == String.class)                     return String.valueOf(userId);

        throw new IllegalStateException("@ActingUserId is only supported on Long/long/String parameters, but "
                + describe(parameter) + " is a " + type.getSimpleName());
    }

    private Object requireString(MethodParameter parameter, String value, String annotation) {
        if (parameter.getParameterType() != String.class) {
            throw new IllegalStateException(annotation + " is only supported on String parameters, but "
                    + describe(parameter) + " is a " + parameter.getParameterType().getSimpleName());
        }
        return value;
    }

    private String describe(MethodParameter parameter) {
        return parameter.getContainingClass().getSimpleName()
             + "#" + (parameter.getMethod() != null ? parameter.getMethod().getName() : "?")
             + " parameter " + parameter.getParameterIndex();
    }
}
