<script setup lang="ts">
/**
 * A component displaying a SVG icon.
 *
 * It displays one group defined in `@/assets/icons.svg` file, specified by `variant` property.
 */
import icons from '@/assets/icons.svg'
import type { URLString } from '@/util/data/urlString'
import type { Icon } from '@/util/iconName'

const props = defineProps<{
  name: Icon | URLString
  title?: string
}>()
</script>

<template>
  <svg viewBox="0 0 16 16" preserveAspectRatio="xMidYMid slice">
    <title v-if="title" v-text="title"></title>
    <use :href="props.name.includes(':') ? props.name : `${icons}#${props.name}`"></use>
  </svg>
</template>

<style scoped>
svg {
  overflow: visible; /* Prevent slight cutting off icons that are using all available space. */
  width: var(--icon-width, var(--icon-size, 16px));
  height: var(--icon-height, var(--icon-size, 16px));
  transform: var(--icon-transform);
  transform-origin: var(--icon-transform-origin, center center);
}
</style>
