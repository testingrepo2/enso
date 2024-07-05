import { createContextStore } from '@/providers'
import type { ToValue } from '@/util/reactivity'
import type Backend from 'enso-common/src/services/Backend'
import { computed, proxyRefs, toValue } from 'vue'

export { injectFn as injectBackend, provideFn as provideBackend }
const { provideFn, injectFn } = createContextStore('backend', (backend: ToValue<Backend>) =>
  proxyRefs({
    backend: computed(() => toValue(backend)),
  }),
)
