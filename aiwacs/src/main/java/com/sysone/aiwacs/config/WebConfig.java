package com.sysone.aiwacs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 화면 주소 연결. HTML은 static 폴더에 있고, 확장자 없는 주소(/policy, /ai)로 열리게 한다.
 * ("/"는 Spring이 static/index.html을 자동으로 보여준다)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/policy").setViewName("forward:/policy.html");
        registry.addViewController("/ai").setViewName("forward:/ai.html");
    }
}
