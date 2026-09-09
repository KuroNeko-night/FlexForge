package com.flexforge.auth;

import com.flexforge.common.PublicApi;

/**
 * 授权拒绝（S2 服务端校验）：已认证但缺少所需角色 → 403 permission_denied。
 */
@PublicApi
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException() {
        super("没有执行该操作的权限");
    }

    public PermissionDeniedException(String message) {
        super(message);
    }
}
