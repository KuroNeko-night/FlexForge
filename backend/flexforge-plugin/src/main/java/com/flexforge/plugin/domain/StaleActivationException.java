package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;

/** 使用过期激活身份的请求被拒（FR-PLUGIN-07）：旧 activationId 不影响当前版本。 */
@PublicApi
public class StaleActivationException extends PluginValidationException {

    public StaleActivationException(String message) {
        super(ErrorCodes.STALE_ACTIVATION, message);
    }

    public StaleActivationException() {
        this("激活身份已过期（插件已停用/升级），请使用当前激活");
    }
}
