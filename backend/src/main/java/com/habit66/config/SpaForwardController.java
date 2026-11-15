package com.habit66.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** SPA 라우팅: `/`와 확장자 없는 한 단계 경로(`/api` 제외)는 index.html 로 보낸다. */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/{path:^(?!api$)[^.]*$}"})
    public String forward() {
        return "forward:/index.html";
    }
}
