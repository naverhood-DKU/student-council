package kr.dku.councilflow.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 브라우저에서 화면 주소(/login, /orgs/3 …)로 바로 들어와도 React 앱이 뜨도록 index.html 로 넘긴다. */
@Controller
class SpaForwardController {

    @GetMapping({"/login", "/signup", "/orgs", "/orgs/**", "/account", "/admin"})
    String forward() {
        return "forward:/index.html";
    }
}
