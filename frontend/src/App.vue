<script setup lang="ts">
/**
 * 应用壳：路由出口 + 全局样式。安全（S5）：全应用仅文本插值，
 * 无 v-html/eval/new Function；元数据只驱动组件选择与文本。
 */
</script>

<template>
  <router-view />
</template>

<style>
/* P12.5：观感值全部改为 --ff-* 令牌消费（tokens.css 定义基线，主题插件覆盖即换肤） */
:root {
  color-scheme: light;
  font-family: var(--ff-font);
  line-height: 1.6;
}

body {
  margin: 0;
  background: var(--ff-bg);
  color: var(--ff-text);
}

a {
  color: var(--ff-primary);
  text-decoration: none;
}
a:hover {
  text-decoration: underline;
}

button {
  padding: var(--ff-space-1) var(--ff-space-3);
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-sm);
  background: var(--ff-surface);
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.state-view {
  padding: 1.5rem;
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.state-view[data-state='error'] {
  background: var(--ff-danger-bg);
  color: var(--ff-danger);
}
.state-view[data-state='denied'] {
  background: var(--ff-warn-bg);
  color: var(--ff-warn);
}
.state-detail {
  font-size: var(--ff-text-sm);
  opacity: 0.8;
}

.hint {
  display: block;
  font-size: 0.78rem;
  color: var(--ff-text-muted);
}

.workbench {
  display: grid;
  grid-template-columns: 14rem 1fr;
  min-height: 100vh;
  /* extension.theme-asset 消费面（FR-PLUGIN-11）：贡献以 CSS 变量注入，
     缺省不设变量即平台默认外观；animation/icon 变量为 P09 示例预留 */
  background-image: var(--ff-theme-background, none);
  background-size: cover;
  background-position: center;
}
.workbench-side {
  background: var(--ff-side-bg);
  color: var(--ff-side-ink);
  padding: 1rem;
  display: flex;
  flex-direction: column;
}
.workbench-side nav ul {
  list-style: none;
  padding: 0;
  margin: 0;
}
.workbench-side nav a {
  display: block;
  padding: 0.4rem 0.6rem;
  color: var(--ff-side-ink);
  border-radius: var(--ff-radius-sm);
  transition: background var(--ff-motion-fast) var(--ff-ease);
}
.workbench-side nav a.router-link-active {
  background: var(--ff-side-active);
}
.menu-static {
  display: block;
  padding: 0.4rem 0.6rem;
  opacity: 0.6;
}
.side-footer {
  margin-top: auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--ff-text-md);
}
.brand {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  margin: 0 0 var(--ff-space-3);
  font-weight: 700;
  letter-spacing: 0.05em;
}
.brand-mark {
  flex: none;
}

.workbench-main {
  padding: 1.5rem 2rem;
  overflow-x: auto;
  animation: ff-fade-in var(--ff-motion-slow) var(--ff-ease) both;
}

.entity-cards {
  list-style: none;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
  gap: 1rem;
}
.entity-cards a {
  display: block;
  padding: 1rem;
  background: var(--ff-surface);
  border-radius: var(--ff-radius-md);
  box-shadow: var(--ff-shadow-1);
  transition:
    box-shadow var(--ff-motion-base) var(--ff-ease),
    transform var(--ff-motion-base) var(--ff-ease);
}
.entity-cards a:hover {
  box-shadow: var(--ff-shadow-2);
  transform: translateY(-2px);
}
.entity-cards .entity-name {
  display: block;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}

.dynamic-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--ff-surface);
  border-radius: var(--ff-radius-md);
  overflow: hidden;
}
.dynamic-table th,
.dynamic-table td {
  padding: var(--ff-space-2) var(--ff-space-3);
  border-bottom: 1px solid var(--ff-border-soft);
  text-align: left;
}
.dynamic-table tbody tr {
  cursor: pointer;
}
.dynamic-table .row-actions button {
  margin-right: 0.3rem;
  font-size: var(--ff-text-sm);
}

.pager {
  display: flex;
  gap: 0.8rem;
  align-items: center;
  margin: 0.8rem 0;
}
.create-link {
  display: inline-block;
  margin-top: 0.5rem;
}

.dynamic-form .form-field {
  margin-bottom: 0.9rem;
  max-width: 26rem;
}
.dynamic-form label {
  display: block;
  font-weight: 600;
  margin-bottom: 0.2rem;
}
.dynamic-form input,
.dynamic-form select {
  padding: 0.4rem;
  width: 100%;
  box-sizing: border-box;
}
.required-mark {
  color: var(--ff-danger);
  margin-left: 0.2rem;
}
.form-error {
  color: var(--ff-danger);
}

.detail-list dt {
  font-weight: 600;
  margin-top: 0.8rem;
}
.detail-list dd {
  margin: 0.1rem 0 0;
}
.detail-actions {
  margin-top: 1.2rem;
  display: flex;
  gap: 0.8rem;
}

.stale-note {
  color: var(--ff-warn);
  font-size: var(--ff-text-md);
}
</style>
