package com.flexforge.plugin.application;

import java.util.Objects;

/** 校验管线协作者内核（参数上限口径，参照 auth AuthKernel）。 */
record PluginImportKernel(ArchiveInspector inspector, ManifestValidator validator,
                          MigrationScriptScanner scanner, DependencyResolver resolver) {

    PluginImportKernel {
        Objects.requireNonNull(inspector, "inspector");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(scanner, "scanner");
        Objects.requireNonNull(resolver, "resolver");
    }
}
