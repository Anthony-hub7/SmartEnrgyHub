package com.smartenergy.collector.config;

import com.smartenergy.collector.web.BodySizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Enregistre BodySizeLimitFilter juste apres RequestIdFilter pour conserver le traceId. */
@Configuration
public class FilterConfig {

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(MeasurementProperties props) {
        FilterRegistrationBean<BodySizeLimitFilter> reg =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(props));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
