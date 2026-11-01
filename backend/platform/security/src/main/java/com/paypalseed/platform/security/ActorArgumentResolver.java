package com.paypalseed.platform.security;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Injects the authenticated {@link Actor} into controller methods; 401 when there is none. */
@Configuration(proxyBeanMethods = false)
public class ActorArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final ActorAuthentication authentication;

    public ActorArgumentResolver(ActorAuthentication authentication) {
        this.authentication = authentication;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Actor.class;
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest,
                                 WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return authentication.authenticate(request).orElseThrow(() -> {
            HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
            if (response != null) {
                // A bare challenge: it must not say whether the token was missing, expired or forged.
                response.setHeader("WWW-Authenticate", "Bearer");
            }
            return new DomainException(CommonErrors.UNAUTHENTICATED, "Sign in to use this route");
        });
    }
}
