<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import {
  ArcElement,
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  Legend,
  LinearScale,
  PieController,
  Tooltip,
} from 'chart.js';

// chart.js v4 裸入口不自动注册控制器（审查 P1-1）：条形/饼所需最小集显式注册
Chart.register(
  BarController,
  BarElement,
  PieController,
  ArcElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
);

/**
 * 图表基建（P22，FR-CHART-01）：Chart.js 封装，条形/饼两种图型。
 * 调色板经 getComputedStyle 读取 --ff-chart-c1..c6 令牌（主题包覆盖即换肤）；
 * prefers-reduced-motion 下动画时长归零；canvas 带 aria 摘要 + 下方紧凑数据表
 * （可访问性与数据核对兜底）。数据由平台校验层保证（categories/values 等长）。
 * 契约：props 视为不可变——父层以 :key/result 替换重建本组件（审查 P3-5），
 * 不监听 props 变化重绘。
 */
const props = defineProps<{
  type: 'bar' | 'pie';
  title: string;
  categories: string[];
  values: number[];
}>();

const FALLBACK_COLOR = '#6366f1';

const canvas = ref<HTMLCanvasElement | null>(null);
let chart: Chart | null = null;

function tokenColor(styles: CSSStyleDeclaration, name: string): string {
  return styles.getPropertyValue(name).trim() || FALLBACK_COLOR;
}

function palette(): string[] {
  const styles = getComputedStyle(document.documentElement);
  return ['c1', 'c2', 'c3', 'c4', 'c5', 'c6'].map((slot) =>
    tokenColor(styles, `--ff-chart-${slot}`),
  );
}

function render(): void {
  if (!canvas.value || !canvas.value.getContext('2d')) {
    // 无 2d 上下文（极老环境/测试环境）：canvas 绘制跳过，数据表兜底呈现
    return;
  }
  const styles = getComputedStyle(document.documentElement);
  const colors = palette();
  // canvas 不解析 CSS 变量：表面色取实值（审查 P2-1）
  const surface = tokenColor(styles, '--ff-surface');
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  chart = new Chart(canvas.value, {
    type: props.type,
    data: {
      labels: props.categories,
      datasets: [
        props.type === 'bar'
          ? {
              label: props.title,
              data: props.values,
              backgroundColor: colors[0],
              borderRadius: 4,
              maxBarThickness: 42,
            }
          : {
              label: props.title,
              data: props.values,
              backgroundColor: props.categories.map((_, index) => colors[index % colors.length]),
              borderColor: surface,
              borderWidth: 2,
            },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: reduced ? false : { duration: 400 },
      plugins: { legend: { display: props.type === 'pie' } },
    },
  });
}

onMounted(render);
onBeforeUnmount(() => {
  chart?.destroy();
  chart = null;
});

/** 展示值：两位舍入，避免浮点尾数直出数据表（审查 P3-3）。 */
function displayValue(value: number): string {
  return String(Math.round(value * 100) / 100);
}

function totalText(): string {
  const sum = props.values.reduce((acc, value) => acc + value, 0);
  return Number.isFinite(sum) ? displayValue(sum) : '—';
}

/** aria 摘要：至多前 5 项，防 50 项级超长朗读（审查 P3-2）。 */
function ariaSummary(): string {
  const head = props.categories
    .slice(0, 5)
    .map((category, index) => `${category} ${displayValue(props.values[index])}`);
  const suffix = props.categories.length > head.length ? ` 等 ${props.categories.length} 项` : '';
  return `${props.title}：${head.join('、')}${suffix}`;
}
</script>

<template>
  <figure class="chart-canvas" data-testid="chart-canvas">
    <div class="chart-frame">
      <canvas ref="canvas" role="img" :aria-label="ariaSummary()"></canvas>
    </div>
    <figcaption class="chart-title">{{ title }}</figcaption>
    <table class="chart-data" data-testid="chart-data">
      <caption class="sr-only">
        {{
          title
        }}
        数据表
      </caption>
      <thead>
        <tr>
          <th scope="col">类别</th>
          <th scope="col">数值</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(category, index) in categories" :key="category">
          <td>{{ category }}</td>
          <td>{{ displayValue(values[index]) }}</td>
        </tr>
      </tbody>
      <tfoot>
        <tr>
          <td>合计</td>
          <td>{{ totalText() }}</td>
        </tr>
      </tfoot>
    </table>
  </figure>
</template>

<style scoped>
.chart-canvas {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.chart-frame {
  position: relative;
  height: 16rem;
}
.chart-title {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
  text-align: center;
}
.chart-data {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--ff-text-sm);
}
.chart-data th,
.chart-data td {
  padding: var(--ff-space-1) var(--ff-space-2);
  border-bottom: 1px solid var(--ff-border-soft);
  text-align: left;
}
.chart-data th {
  font-weight: 500;
  color: var(--ff-text-muted);
}
.chart-data tfoot td {
  font-weight: 600;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
