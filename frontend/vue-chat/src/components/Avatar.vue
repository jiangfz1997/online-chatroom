<template>
  <div
    class="avatar"
    :style="avatarStyle"
    :title="name || seed"
    role="img"
    :aria-label="name || seed"
  >
    <span class="avatar-initials" :style="{ fontSize: fontSize }">{{ initials }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** Deterministic seed (username or avatarSeed) driving the gradient + fallback initials. */
    seed: string
    /** Display name used for the initials; falls back to the seed. */
    name?: string
    /** Diameter in pixels. */
    size?: number
  }>(),
  { size: 40, name: '' }
)

// FNV-1a-ish string hash → stable non-negative int.
function hash(str: string): number {
  let h = 2166136261
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

const label = computed(() => (props.name && props.name.trim()) || props.seed || '?')

const initials = computed(() => {
  const parts = label.value.trim().split(/\s+/).filter(Boolean)
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase()
  }
  return label.value.trim().slice(0, 2).toUpperCase()
})

const avatarStyle = computed(() => {
  const h = hash(props.seed || label.value)
  const hue1 = h % 360
  const hue2 = (hue1 + 40 + (h % 60)) % 360
  return {
    width: `${props.size}px`,
    height: `${props.size}px`,
    backgroundImage: `linear-gradient(135deg, hsl(${hue1} 62% 52%), hsl(${hue2} 60% 42%))`,
  }
})

const fontSize = computed(() => `${Math.round(props.size * 0.4)}px`)
</script>

<style scoped>
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-full);
  flex-shrink: 0;
  color: #fff;
  user-select: none;
  overflow: hidden;
}
.avatar-initials {
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.02em;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}
</style>
