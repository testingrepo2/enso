import { useEvent } from '@/composables/events'
import type { Ref } from 'vue'

/** Automatically `blur` the currently active element on any mouse click outside of `root`.
 * It is useful when other elements may capture pointer events, preventing default browser behavior for focus change. */
export function useAutoBlur(root: Ref<HTMLElement | SVGElement | MathMLElement | undefined>) {
  useEvent(window, 'pointerdown', (event) => blurIfNecessary(root, event), { capture: true })
}

/** Returns true if the target of the event is in the DOM subtree of the given `area` element. */
export function targetIsOutside(
  e: Event,
  area: Ref<HTMLElement | SVGElement | MathMLElement | undefined>,
): boolean {
  return !!area.value && e.target instanceof Element && !area.value.contains(e.target)
}

/** Internal logic of `useAutoBlur`, useful for direct usage in some cases.
 * Returns `true` if `event` does not target `root` and blurs currently active element.
 * Otherwise returns `false` and does nothing. */
export function blurIfNecessary(
  root: Ref<HTMLElement | SVGElement | MathMLElement | undefined>,
  event: MouseEvent,
): boolean {
  if (!root.value?.contains(document.activeElement) || !targetIsOutside(event, root)) return false
  if (
    !(document.activeElement instanceof HTMLElement) &&
    !(document.activeElement instanceof SVGElement) &&
    !(document.activeElement instanceof MathMLElement)
  )
    return false
  document.activeElement.blur()
  return true
}
