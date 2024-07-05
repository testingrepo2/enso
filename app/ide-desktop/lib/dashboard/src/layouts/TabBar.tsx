/** @file Switcher to choose the currently visible full-screen page. */
import * as React from 'react'

import invariant from 'tiny-invariant'

import type * as text from 'enso-common/src/text'

import * as textProvider from '#/providers/TextProvider'

import * as aria from '#/components/aria'
import * as ariaComponents from '#/components/AriaComponents'
import FocusArea from '#/components/styled/FocusArea'

import * as tailwindMerge from '#/utilities/tailwindMerge'

// =================
// === Constants ===
// =================

/** The corner radius of the tabs. */
const TAB_RADIUS_PX = 24

// =====================
// === TabBarContext ===
// =====================

/** Context for a {@link TabBarContext}. */
interface TabBarContextValue {
  readonly updateClipPath: (element: HTMLDivElement | null) => void
  readonly observeElement: (element: HTMLElement) => () => void
}

const TabBarContext = React.createContext<TabBarContextValue | null>(null)

/** Custom hook to get tab bar context. */
function useTabBarContext() {
  const context = React.useContext(TabBarContext)
  invariant(context, '`useTabBarContext` must be used inside a `<TabBar />`')
  return context
}

// ==============
// === TabBar ===
// ==============

/** Props for a {@link TabBar}. */
export interface TabBarProps extends Readonly<React.PropsWithChildren> {}

/** Switcher to choose the currently visible full-screen page. */
export default function TabBar(props: TabBarProps) {
  const { children } = props
  const cleanupResizeObserverRef = React.useRef(() => {})
  const backgroundRef = React.useRef<HTMLDivElement | null>(null)
  const selectedTabRef = React.useRef<HTMLDivElement | null>(null)
  const [resizeObserver] = React.useState(
    () =>
      new ResizeObserver(() => {
        updateClipPath(selectedTabRef.current)
      })
  )
  const [updateClipPath] = React.useState(() => {
    return (element: HTMLDivElement | null) => {
      const backgroundElement = backgroundRef.current
      if (backgroundElement != null) {
        selectedTabRef.current = element
        if (element == null) {
          backgroundElement.style.clipPath = ''
        } else {
          const bounds = element.getBoundingClientRect()
          const rootBounds = backgroundElement.getBoundingClientRect()
          const tabLeft = bounds.left - rootBounds.left
          const tabRight = bounds.right - rootBounds.left
          const segments = [
            'M 0 0',
            `L ${rootBounds.width} 0`,
            `L ${rootBounds.width} ${rootBounds.height}`,
            `L ${tabRight + TAB_RADIUS_PX} ${rootBounds.height}`,
            `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 1 ${tabRight} ${rootBounds.height - TAB_RADIUS_PX}`,
            `L ${tabRight} ${TAB_RADIUS_PX}`,
            `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 0 ${tabRight - TAB_RADIUS_PX} 0`,
            `L ${tabLeft + TAB_RADIUS_PX} 0`,
            `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 0 ${tabLeft} ${TAB_RADIUS_PX}`,
            `L ${tabLeft} ${rootBounds.height - TAB_RADIUS_PX}`,
            `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 1 ${tabLeft - TAB_RADIUS_PX} ${rootBounds.height}`,
            `L 0 ${rootBounds.height}`,
            'Z',
          ]
          backgroundElement.style.clipPath = `path("${segments.join(' ')}")`
        }
      }
    }
  })

  const updateResizeObserver = (element: HTMLElement | null) => {
    cleanupResizeObserverRef.current()
    if (element == null) {
      cleanupResizeObserverRef.current = () => {}
    } else {
      resizeObserver.observe(element)
      cleanupResizeObserverRef.current = () => {
        resizeObserver.unobserve(element)
      }
    }
  }

  return (
    <TabBarContext.Provider
      value={{
        updateClipPath,
        observeElement: element => {
          resizeObserver.observe(element)

          return () => {
            resizeObserver.unobserve(element)
          }
        },
      }}
    >
      <div className="relative flex grow">
        <div
          ref={element => {
            backgroundRef.current = element
            updateResizeObserver(element)
          }}
          className="pointer-events-none absolute inset-0 bg-primary/5"
        />
        <Tabs>{children}</Tabs>
      </div>
    </TabBarContext.Provider>
  )
}

// ============
// === Tabs ===
// ============

/** Props for a {@link TabsInternal}. */
export interface InternalTabsProps extends Readonly<React.PropsWithChildren> {}

/** A tab list in a {@link TabBar}. */
function TabsInternal(props: InternalTabsProps, ref: React.ForwardedRef<HTMLDivElement>) {
  const { children } = props
  return (
    <FocusArea direction="horizontal">
      {innerProps => (
        <div
          className="flex h-12 shrink-0 grow cursor-default items-center rounded-full"
          {...aria.mergeProps<React.JSX.IntrinsicElements['div']>()(innerProps, { ref })}
        >
          {children}
        </div>
      )}
    </FocusArea>
  )
}

const Tabs = React.forwardRef(TabsInternal)

// ===========
// === Tab ===
// ===========

/** Props for a {@link Tab}. */
interface InternalTabProps extends Readonly<React.PropsWithChildren> {
  readonly isActive: boolean
  readonly icon: string
  readonly labelId: text.TextId
  /** When the promise is in flight, the tab icon will instead be a loading spinner. */
  readonly loadingPromise?: Promise<unknown>
  readonly onPress: () => void
  readonly onClose?: () => void
}

/** A tab in a {@link TabBar}. */
export function Tab(props: InternalTabProps) {
  const { isActive, icon, labelId, loadingPromise, children, onPress, onClose } = props
  const { updateClipPath, observeElement } = useTabBarContext()
  const ref = React.useRef<HTMLDivElement | null>(null)
  const { getText } = textProvider.useText()
  const [isLoading, setIsLoading] = React.useState(loadingPromise != null)

  React.useLayoutEffect(() => {
    if (isActive) {
      updateClipPath(ref.current)
    }
  }, [isActive, updateClipPath])

  React.useEffect(() => {
    if (ref.current) {
      return observeElement(ref.current)
    } else {
      return () => {}
    }
  }, [observeElement])

  React.useEffect(() => {
    if (loadingPromise) {
      setIsLoading(true)
      loadingPromise.then(
        () => {
          setIsLoading(false)
        },
        () => {
          setIsLoading(false)
        }
      )
    } else {
      setIsLoading(false)
    }
  }, [loadingPromise])

  return (
    <div
      ref={ref}
      className={tailwindMerge.twMerge(
        'group relative h-full',
        !isActive && 'hover:enabled:bg-frame'
      )}
    >
      <ariaComponents.Button
        size="custom"
        variant="custom"
        loaderPosition="icon"
        icon={({ isFocusVisible, isHovered }) =>
          (isFocusVisible || isHovered) && onClose ? (
            <div className="mt-[1px] flex h-4 w-4 items-center justify-center">
              <ariaComponents.CloseButton onPress={onClose} />
            </div>
          ) : (
            icon
          )
        }
        isDisabled={false}
        isActive={isActive}
        loading={isActive ? false : isLoading}
        aria-label={getText(labelId)}
        tooltip={false}
        className={tailwindMerge.twMerge('relative flex h-full items-center gap-3 px-4')}
        onPress={onPress}
      >
        {children}
      </ariaComponents.Button>
    </div>
  )
}
