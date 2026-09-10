<script setup lang="ts">
import LayoutRenderer from '@/components/LayoutRenderer.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 工作台首页：workbench.main 容器改由布局渲染器接管（extension.layout 消费面）。
 * 缺省布局 = main 槽位内置实体入口部件；插件布局贡献（P08）可声明槽位与部件
 * 编排，撤销后即时恢复本缺省布局。标题经 t() 可被 locale 插件覆盖（P15）。
 */
const DEFAULT_SLOTS = [{ name: 'main', widgetKeys: ['workbench.entities'] }];
</script>

<template>
  <section class="home-view ff-animate-rise">
    <h2 class="ff-page-title">{{ t('home.title', '工作台') }}</h2>
    <LayoutRenderer target="workbench.main" :default-slots="DEFAULT_SLOTS" />
  </section>
</template>
