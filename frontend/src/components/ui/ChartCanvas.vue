<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { Chart } from 'chart.js';

/**
 * 图表基建（P22，FR-CHART-01）：Chart.js 封装，条形/饼两种图型。
 * 调色板经 getComputedStyle 读取 --ff-chart-c1..c6 令牌（主题包覆盖即换肤）；
 * prefers-reduced-motion 下动画时长归零；canvas 带 aria 摘要 + 下方紧凑数据表
 * （可访问性与数据核对兜底）。数据由平台校验层保证（categories/values 等长）。
 */
const props = defineProps<{
  type: 'bar' | 'pie';
  title: string;
  categories: string[];
  values: number[];
}>();

const canvas = ref<HTMLCanvasElement | null>(null);
let chart: Chart | null = null;

function palette(): string[] {
  const styles = getComputedStyle(document.documentElement);
  return ['c1', 'c2', 'c3', 'c4', 'c5', 'c6'].map(
    (slot) => styles.getPropertyValue(`--ff-chart-${slot}`).trim() || '#6366f1',
  );
}

function total(): number {
  return props.values.reduce((sum, value) => sum + value, 0);
}

function render(): void {
  if (!canvas.value || !canvas.value.getContext('2d')) {
    // 无 2d 上下文（极老环境/测试环境）：canvas 绘制跳过，数据表兜底呈现
    return;
  }
  const colors = palette();
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
              borderColor: 'var(--ff-surface)',
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
</script>

<template>
  <figure class="chart-canvas" data-testid="chart-canvas">
    <div class="chart-frame">
      <canvas
        ref="canvas"
        role="img"
        :aria-label="`${title}：${categories
          .map((category, index) => `${category} ${values[index]}`)
          .join('、')}`"
      ></canvas>
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
          <td>{{ values[index] }}</td>
        </tr>
      </tbody>
      <tfoot>
        <tr>
          <td>合计</td>
          <td>{{ Math.round(total() * 100) / 100 }}</td>
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
