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
:root {
  color-scheme: light;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  line-height: 1.6;
}

body {
  margin: 0;
  background: #f5f6f8;
  color: #1f2430;
}

a {
  color: #2b5fd9;
  text-decoration: none;
}
a:hover {
  text-decoration: underline;
}

button {
  padding: 0.25rem 0.75rem;
  border: 1px solid #c4cad6;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.state-view {
  padding: 1.5rem;
  border-radius: 6px;
  background: #eef1f6;
  color: #4a5266;
}
.state-view[data-state='error'] {
  background: #fbeaea;
  color: #8a2f2f;
}
.state-view[data-state='denied'] {
  background: #fdf3e4;
  color: #8a5a12;
}
.state-detail {
  font-size: 0.85rem;
  opacity: 0.8;
}

.hint {
  display: block;
  font-size: 0.78rem;
  color: #6a7286;
}

.login-view {
  max-width: 22rem;
  margin: 8vh auto;
  padding: 2rem;
  background: #fff;
  border-radius: 8px;
}
.login-form label {
  display: block;
  margin-bottom: 0.8rem;
}
.login-form input {
  display: block;
  width: 100%;
  margin-top: 0.25rem;
  padding: 0.4rem;
}
.login-error {
  color: #8a2f2f;
}

.workbench {
  display: grid;
  grid-template-columns: 14rem 1fr;
  min-height: 100vh;
}
.workbench-side {
  background: #1f2430;
  color: #dfe3ec;
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
  color: #dfe3ec;
  border-radius: 4px;
}
.workbench-side nav a.router-link-active {
  background: #2b3350;
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
  font-size: 0.85rem;
}
.brand {
  font-weight: 700;
  letter-spacing: 0.05em;
}

.workbench-main {
  padding: 1.5rem 2rem;
  overflow-x: auto;
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
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgb(0 0 0 / 8%);
}
.entity-cards .entity-name {
  display: block;
  color: #6a7286;
  font-size: 0.8rem;
}

.dynamic-table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
  border-radius: 6px;
  overflow: hidden;
}
.dynamic-table th,
.dynamic-table td {
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #e6e9f0;
  text-align: left;
}
.dynamic-table tbody tr {
  cursor: pointer;
}
.dynamic-table .row-actions button {
  margin-right: 0.3rem;
  font-size: 0.8rem;
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
  color: #a33;
  margin-left: 0.2rem;
}
.form-error {
  color: #8a2f2f;
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
  color: #8a5a12;
  font-size: 0.85rem;
}
</style>
