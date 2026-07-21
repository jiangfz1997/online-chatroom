<template>
  <svg
    class="icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <circle
      v-for="(c, i) in shape.circles"
      :key="'c' + i"
      :cx="c.cx"
      :cy="c.cy"
      :r="c.r"
    />
    <path v-for="(d, i) in shape.paths" :key="'p' + i" :d="d" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface IconShape {
  paths: string[]
  circles: { cx: number; cy: number; r: number }[]
}

const props = withDefaults(
  defineProps<{
    name: string
    size?: number
  }>(),
  { size: 18 }
)

// Minimal Feather-style icon set (24x24 grid).
const ICONS: Record<string, IconShape> = {
  search: { circles: [{ cx: 11, cy: 11, r: 8 }], paths: ['M21 21l-4.3-4.3'] },
  logout: { circles: [], paths: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'] },
  info: { circles: [{ cx: 12, cy: 12, r: 10 }], paths: ['M12 16v-4', 'M12 8h.01'] },
  send: { circles: [], paths: ['M22 2 11 13', 'M22 2 15 22l-4-9-9-4 20-7z'] },
  plus: { circles: [], paths: ['M12 5v14', 'M5 12h14'] },
  close: { circles: [], paths: ['M18 6 6 18', 'M6 6l12 12'] },
  lock: { circles: [], paths: ['M5 11h14v10H5z', 'M8 11V7a4 4 0 0 1 8 0v4'] },
  user: { circles: [{ cx: 12, cy: 8, r: 4 }], paths: ['M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1'] },
  edit: { circles: [], paths: ['M12 20h9', 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z'] },
  chevronLeft: { circles: [], paths: ['M15 18l-6-6 6-6'] },
}

const EMPTY: IconShape = { paths: [], circles: [] }
const shape = computed<IconShape>(() => ICONS[props.name] ?? EMPTY)
</script>

<style scoped>
.icon {
  display: inline-block;
  vertical-align: middle;
  flex-shrink: 0;
}
</style>
