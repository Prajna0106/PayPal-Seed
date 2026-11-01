package com.paypalseed.agentgateway.infrastructure.web;

import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Injects the verified {@link AgentPrincipal} from a run token into agent endpoints; 401 when there is none. */
@Configuration(proxyBeanMethods = false)
class AgentPrincipalResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private static final String BEARER = "Bearer ";

    private final AgentTokens tokens;

    AgentPrincipalResolver(AgentTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AgentPrincipal.class;
    }

    @Override
    public AgentPrincipal resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                          WebDataBinderFactory binderFactory) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new DomainException(CommonErrors.UNAUTHENTICATED, "An agent run token is required");
        }
        return tokens.verify(authorization.substring(BEARER.length()).trim())
                .orElseThrow(() -> new DomainException(CommonErrors.UNAUTHENTICATED, "The agent token is unknown, expired or revoked"));
    }
}
