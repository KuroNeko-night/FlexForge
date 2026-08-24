package com.flexforge.system.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.common.contract.NavigationContribution;
import com.flexforge.system.application.MenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单接口（docs/09 P03：三类角色在菜单上表现不同）：内置平台菜单 + extension.navigation
 * 运行时贡献，按当前用户角色过滤（S2：服务端过滤，前端隐藏只是体验）。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    public List<NavigationContribution> currentMenus(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        return menuService.menusFor(principal);
    }
}
