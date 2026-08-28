package com.flexforge.plugin.application;

import com.flexforge.common.api.ErrorCodes;
import com.flexforge.plugin.domain.DependencySpec;
import com.flexforge.plugin.domain.PluginValidationException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 依赖解析（docs/09 P07：依赖缺失能指出具体插件和版本范围）：
 * 对已导入的插件版本解析 `*` / `^X.Y.Z` / `X.Y.Z` 范围，取满足范围的最高版本。
 */
@Component
public class DependencyResolver {

    /** 逐依赖解析；任一缺失抛 dependency_missing（消息含插件与范围）。 */
    public void resolve(List<DependencySpec> dependencies, java.util.function.Function<String, List<String>> versionsOf) {
        for (DependencySpec dependency : dependencies) {
            String matched = highestMatching(versionsOf.apply(dependency.pluginId()), dependency.versionRange());
            if (matched == null) {
                throw new PluginValidationException(ErrorCodes.DEPENDENCY_MISSING,
                        "依赖缺失: " + dependency.pluginId() + " 需满足 " + dependency.versionRange()
                                + "，当前未导入任何满足版本");
            }
        }
    }

    private static String highestMatching(List<String> available, String range) {
        String best = null;
        for (String version : available) {
            if (!satisfies(version, range)) {
                continue;
            }
            if (best == null || compare(version, best) > 0) {
                best = version;
            }
        }
        return best;
    }

    static boolean satisfies(String version, String range) {
        if ("*".equals(range)) {
            return true;
        }
        if (range.startsWith("^")) {
            String base = range.substring(1);
            if (compare(version, base) < 0) {
                return false;
            }
            int[] b = parts(base);
            // npm 语义：主版本 >0 锁主版本；0.x 锁次版本
            if (b[0] > 0) {
                return major(version) == b[0];
            }
            return major(version) == 0 && minor(version) == b[1];
        }
        return compare(version, range) == 0;
    }

    static int compare(String left, String right) {
        int[] a = parts(left);
        int[] b = parts(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int major(String version) {
        return parts(version)[0];
    }

    private static int minor(String version) {
        return parts(version)[1];
    }

    private static int[] parts(String version) {
        String[] segments = version.split("\\.");
        return new int[]{Integer.parseInt(segments[0]), Integer.parseInt(segments[1]),
                Integer.parseInt(segments[2])};
    }
}
