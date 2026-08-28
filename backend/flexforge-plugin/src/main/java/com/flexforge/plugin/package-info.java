/**
 * 插件包校验与版本存储模块（docs/08 §2-§4；ADR-0002 Level 1、ADR-0005 迁移 runner）：
 * 上传安全基线、plugin.json Schema 校验（schemaVersion 分派）、迁移脚本校验层、
 * 依赖解析与幂等导入。分层 api → application → domain → infrastructure（docs/10 R1）。
 */
package com.flexforge.plugin;
