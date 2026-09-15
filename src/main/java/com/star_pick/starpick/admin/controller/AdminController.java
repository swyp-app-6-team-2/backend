package com.star_pick.starpick.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminController {

    @GetMapping
    public String root() {
        return "redirect:/admin/inquiries";
    }

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }
}
