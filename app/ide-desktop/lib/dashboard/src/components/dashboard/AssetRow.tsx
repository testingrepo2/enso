/** @file A table row for an arbitrary asset. */
import * as React from 'react'

import BlankIcon from 'enso-assets/blank.svg'

import * as eventHooks from '#/hooks/eventHooks'
import * as setAssetHooks from '#/hooks/setAssetHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as authProvider from '#/providers/AuthProvider'
import * as modalProvider from '#/providers/ModalProvider'

import AssetEventType from '#/events/AssetEventType'
import AssetListEventType from '#/events/AssetListEventType'

import AssetContextMenu from '#/layouts/dashboard/AssetContextMenu'
import type * as assetsTable from '#/layouts/dashboard/AssetsTable'

import * as assetRowUtils from '#/components/dashboard/AssetRow/assetRowUtils'
import * as columnModule from '#/components/dashboard/column'
import * as columnUtils from '#/components/dashboard/column/columnUtils'
import StatelessSpinner, * as statelessSpinner from '#/components/StatelessSpinner'

import * as backendModule from '#/services/Backend'

import AssetTreeNode from '#/utilities/AssetTreeNode'
import * as dateTime from '#/utilities/dateTime'
import * as download from '#/utilities/download'
import * as drag from '#/utilities/drag'
import * as errorModule from '#/utilities/error'
import * as eventModule from '#/utilities/event'
import * as indent from '#/utilities/indent'
import * as object from '#/utilities/object'
import * as permissions from '#/utilities/permissions'
import * as set from '#/utilities/set'
import Visibility, * as visibilityModule from '#/utilities/visibility'

// =================
// === Constants ===
// =================

/** The amount of time (in milliseconds) the drag item must be held over this component
 * to make a directory row expand. */
const DRAG_EXPAND_DELAY_MS = 500

/** Placeholder row for directories that are empty. */
const EMPTY_DIRECTORY_PLACEHOLDER = <span className="px-2 opacity-75">This folder is empty.</span>

// ================
// === AssetRow ===
// ================

/** Common properties for state and setters passed to event handlers on an {@link AssetRow}. */
export interface AssetRowInnerProps {
  key: backendModule.AssetId
  item: AssetTreeNode
  setItem: React.Dispatch<React.SetStateAction<AssetTreeNode>>
  state: assetsTable.AssetsTableState
  rowState: assetsTable.AssetRowState
  setRowState: React.Dispatch<React.SetStateAction<assetsTable.AssetRowState>>
}

/** Props for an {@link AssetRow}. */
export interface AssetRowProps
  extends Omit<JSX.IntrinsicElements['tr'], 'onClick' | 'onContextMenu'> {
  item: AssetTreeNode
  state: assetsTable.AssetsTableState
  visibility: Visibility | null
  columns: columnUtils.Column[]
  selected: boolean
  setSelected: (selected: boolean) => void
  isSoleSelectedItem: boolean
  allowContextMenu: boolean
  onClick: (props: AssetRowInnerProps, event: React.MouseEvent) => void
  onContextMenu?: (props: AssetRowInnerProps, event: React.MouseEvent<HTMLTableRowElement>) => void
}

/** A row containing an {@link backendModule.AnyAsset}. */
export default function AssetRow(props: AssetRowProps) {
  const { item: rawItem, visibility: visibilityRaw, selected, isSoleSelectedItem } = props
  const { setSelected, allowContextMenu, onContextMenu, state, columns, onClick } = props
  const { isCloud, assetEvents, dispatchAssetEvent, dispatchAssetListEvent } = state
  const { nodeMap, setAssetPanelProps, doToggleDirectoryExpansion, doCopy, doCut, doPaste } = state

  const { organization, user } = authProvider.useNonPartialUserSession()
  const { setModal, unsetModal } = modalProvider.useSetModal()
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const [isDraggedOver, setIsDraggedOver] = React.useState(false)
  const [item, setItem] = React.useState(rawItem)
  const dragOverTimeoutHandle = React.useRef<number | null>(null)
  const smartAsset = item.item
  const asset = smartAsset.value
  const [insertionVisibility, setInsertionVisibility] = React.useState(Visibility.visible)
  const [rowState, setRowState] = React.useState<assetsTable.AssetRowState>(() =>
    object.merge(assetRowUtils.INITIAL_ROW_STATE, { setVisibility: setInsertionVisibility })
  )
  const key = AssetTreeNode.getKey(item)
  const setAsset = setAssetHooks.useSetAsset(asset, setItem)
  const visibility =
    visibilityRaw == null || visibilityRaw === Visibility.visible
      ? insertionVisibility
      : visibilityRaw
  const hidden = visibility === Visibility.hidden

  React.useEffect(() => {
    setItem(rawItem)
  }, [rawItem])

  // Materialize the asset on the backend. If it already exists, this will not send a request to
  // the backend.
  React.useEffect(() => {
    const materializedOrPromise = smartAsset.materialize()
    if (!(materializedOrPromise instanceof Promise)) {
      setAsset(materializedOrPromise.value)
    } else {
      void (async () => {
        try {
          rowState.setVisibility(Visibility.faded)
          const materialized = await materializedOrPromise
          rowState.setVisibility(Visibility.visible)
          setAsset(materialized.value)
          if (
            backendModule.assetIsProject(asset) &&
            asset.projectState.type === backendModule.ProjectState.placeholder &&
            backendModule.assetIsProject(materialized.value)
          ) {
            dispatchAssetEvent({
              type: AssetEventType.openProject,
              id: materialized.value.id,
              shouldAutomaticallySwitchPage: true,
              runInBackground: false,
            })
          }
        } catch (error) {
          rowState.setVisibility(Visibility.visible)
        }
      })()
    }
    // This MUST only run once, on initialization.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  React.useEffect(() => {
    // Mutation is HIGHLY INADVISABLE in React, however it is useful here as we want to avoid
    // re - rendering the parent.
    // @ts-expect-error Because `smartAsset` is of an unknown type, its parameter is contravariant.
    // However, this is safe because the type of an asset cannot change.
    rawItem.item = smartAsset.withValue(asset)
    // FIXME: Must this be omitted?
    // `smartAsset` is NOT a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asset, rawItem])

  React.useEffect(() => {
    if (selected && insertionVisibility !== Visibility.visible) {
      setSelected(false)
    }
  }, [selected, insertionVisibility, /* should never change */ setSelected])

  const doCopyOnBackend = React.useCallback(
    async (newParentId: backendModule.DirectoryId | null) => {
      try {
        setAsset(oldAsset =>
          object.merge(oldAsset, {
            title: oldAsset.title + ' (copy)',
            labels: [],
            permissions: permissions.tryGetSingletonOwnerPermission(organization, user),
            modifiedAt: dateTime.toRfc3339(new Date()),
          })
        )
        newParentId ??= organization?.rootDirectory().value.id ?? backendModule.DirectoryId('')
        const copiedAsset = await smartAsset.copy(
          newParentId,
          nodeMap.current.get(newParentId)?.item.value.title ?? '(unknown)'
        )
        setAsset(
          // This is SAFE, as the type of the copied asset is guaranteed to be the same
          // as the type of the original asset.
          // eslint-disable-next-line no-restricted-syntax
          object.merger(copiedAsset.asset as Partial<backendModule.AnyAsset>)
        )
      } catch (error) {
        toastAndLog(`Could not copy '${asset.title}'`, error)
        // Delete the new component representing the asset that failed to insert.
        dispatchAssetListEvent({
          type: AssetListEventType.delete,
          key: item.key,
        })
      }
    },
    [
      organization,
      user,
      smartAsset,
      asset,
      item.key,
      /* should never change */ nodeMap,
      /* should never change */ setAsset,
      /* should never change */ toastAndLog,
      /* should never change */ dispatchAssetListEvent,
    ]
  )

  const doMove = React.useCallback(
    async (
      newParentKey: backendModule.AssetId | null,
      newParent: backendModule.SmartDirectory | null
    ) => {
      const rootDirectory = organization?.rootDirectory()
      newParentKey ??= rootDirectory?.value.id ?? null
      newParent ??= rootDirectory ?? null
      if (newParentKey != null && newParent != null) {
        const nonNullNewParentKey = newParentKey
        const nonNullNewParent = newParent
        try {
          dispatchAssetListEvent({
            type: AssetListEventType.move,
            newParentKey: nonNullNewParentKey,
            newParent: nonNullNewParent,
            key: item.key,
            item: smartAsset,
          })
          setItem(oldItem =>
            oldItem.with({ directoryKey: nonNullNewParentKey, directory: nonNullNewParent })
          )
          await smartAsset.update({ parentDirectoryId: nonNullNewParent.value.id })
        } catch (error) {
          toastAndLog(`Could not move '${smartAsset.value.title}'`, error)
          setItem(oldItem =>
            oldItem.with({ directoryKey: item.directoryKey, directory: item.directory })
          )
          // Move the asset back to its original position.
          dispatchAssetListEvent({
            type: AssetListEventType.move,
            newParentKey: item.directoryKey,
            newParent: item.directory,
            key: item.key,
            item: smartAsset,
          })
        }
      }
    },
    [
      organization,
      smartAsset,
      item.directory,
      item.directoryKey,
      item.key,
      /* should never change */ toastAndLog,
      /* should never change */ dispatchAssetListEvent,
    ]
  )

  React.useEffect(() => {
    if (isSoleSelectedItem) {
      setAssetPanelProps({ item, setItem })
    }
  }, [item, isSoleSelectedItem, /* should never change */ setAssetPanelProps])

  const doDelete = React.useCallback(async () => {
    setInsertionVisibility(Visibility.hidden)
    if (smartAsset.type === backendModule.AssetType.directory) {
      dispatchAssetListEvent({
        type: AssetListEventType.closeFolder,
        folder: smartAsset,
        // This is SAFE, as this asset is already known to be a directory.
        // eslint-disable-next-line no-restricted-syntax
        key: item.key as backendModule.DirectoryId,
      })
    }
    try {
      dispatchAssetListEvent({ type: AssetListEventType.willDelete, key: item.key })
      if (smartAsset.type === backendModule.AssetType.project && !isCloud) {
        if (
          smartAsset.value.projectState.type !== backendModule.ProjectState.placeholder &&
          smartAsset.value.projectState.type !== backendModule.ProjectState.closed
        ) {
          await smartAsset.open()
        }
        try {
          await smartAsset.close()
        } catch {
          // Ignored. The project was already closed.
        }
      }
      await smartAsset.delete()
      dispatchAssetListEvent({
        type: AssetListEventType.delete,
        key: item.key,
      })
    } catch (error) {
      setInsertionVisibility(Visibility.visible)
      toastAndLog(
        errorModule.tryGetMessage(error)?.slice(0, -1) ??
          `Could not delete ${backendModule.ASSET_TYPE_NAME[smartAsset.type]}`
      )
    }
  }, [
    isCloud,
    dispatchAssetListEvent,
    smartAsset,
    /* should never change */ item.key,
    /* should never change */ toastAndLog,
  ])

  const doRestore = React.useCallback(async () => {
    // Visually, the asset is deleted from the Trash view.
    setInsertionVisibility(Visibility.hidden)
    try {
      await smartAsset.undoDelete()
      dispatchAssetListEvent({ type: AssetListEventType.delete, key: item.key })
    } catch (error) {
      setInsertionVisibility(Visibility.visible)
      toastAndLog(`Unable to restore ${backendModule.ASSET_TYPE_NAME[smartAsset.type]}`, error)
    }
  }, [
    dispatchAssetListEvent,
    smartAsset,
    /* should never change */ item.key,
    /* should never change */ toastAndLog,
  ])

  eventHooks.useEventHandler(assetEvents, async event => {
    switch (event.type) {
      // These events are handled in the specific `NameColumn` files.
      case AssetEventType.updateFiles:
      case AssetEventType.openProject:
      case AssetEventType.closeProject:
      case AssetEventType.cancelOpeningAllProjects: {
        break
      }
      case AssetEventType.copy: {
        if (event.ids.has(item.key)) {
          await doCopyOnBackend(event.newParent.value.id)
        }
        break
      }
      case AssetEventType.cut: {
        if (event.ids.has(item.key)) {
          setInsertionVisibility(Visibility.faded)
        }
        break
      }
      case AssetEventType.cancelCut: {
        if (event.ids.has(item.key)) {
          setInsertionVisibility(Visibility.visible)
        }
        break
      }
      case AssetEventType.move: {
        if (event.ids.has(item.key)) {
          setInsertionVisibility(Visibility.visible)
          await doMove(event.newParentKey, event.newParent)
        }
        break
      }
      case AssetEventType.delete: {
        if (event.ids.has(item.key)) {
          await doDelete()
        }
        break
      }
      case AssetEventType.restore: {
        if (event.ids.has(item.key)) {
          await doRestore()
        }
        break
      }
      case AssetEventType.download: {
        if (event.ids.has(item.key)) {
          if (isCloud) {
            if (smartAsset.type !== backendModule.AssetType.file) {
              toastAndLog('Cannot download assets that are not files')
            } else {
              try {
                const details = await smartAsset.getDetails()
                const file = details.file
                download.download(download.s3URLToHTTPURL(file.path), asset.title)
              } catch (error) {
                toastAndLog('Could not download file', error)
              }
            }
          } else {
            download.download(
              `./api/project-manager/projects/${asset.id}/enso-project`,
              `${asset.title}.enso-project`
            )
          }
        }
        break
      }
      case AssetEventType.downloadSelected: {
        if (selected) {
          if (isCloud) {
            if (smartAsset.type !== backendModule.AssetType.file) {
              toastAndLog('Cannot download assets that are not files')
            } else {
              try {
                const details = await smartAsset.getDetails()
                const file = details.file
                download.download(download.s3URLToHTTPURL(file.path), asset.title)
              } catch (error) {
                toastAndLog('Could not download selected files', error)
              }
            }
          } else {
            download.download(
              `./api/project-manager/projects/${asset.id}/enso-project`,
              `${asset.title}.enso-project`
            )
          }
        }
        break
      }
      case AssetEventType.removeSelf: {
        // This is not triggered from the asset list, so it uses `item.id` instead of `key`.
        if (event.id === asset.id && user != null) {
          setInsertionVisibility(Visibility.hidden)
          try {
            await smartAsset.setPermissions({ action: null, userSubjects: [user.id] })
            dispatchAssetListEvent({ type: AssetListEventType.delete, key: item.key })
          } catch (error) {
            setInsertionVisibility(Visibility.visible)
            toastAndLog(null, error)
          }
        }
        break
      }
      case AssetEventType.temporarilyAddLabels: {
        const labels = event.ids.has(item.key) ? event.labelNames : set.EMPTY
        setRowState(oldRowState =>
          oldRowState.temporarilyAddedLabels === labels &&
          oldRowState.temporarilyRemovedLabels === set.EMPTY
            ? oldRowState
            : object.merge(oldRowState, {
                temporarilyAddedLabels: labels,
                temporarilyRemovedLabels: set.EMPTY,
              })
        )
        break
      }
      case AssetEventType.temporarilyRemoveLabels: {
        const labels = event.ids.has(item.key) ? event.labelNames : set.EMPTY
        setRowState(oldRowState =>
          oldRowState.temporarilyAddedLabels === set.EMPTY &&
          oldRowState.temporarilyRemovedLabels === labels
            ? oldRowState
            : object.merge(oldRowState, {
                temporarilyAddedLabels: set.EMPTY,
                temporarilyRemovedLabels: labels,
              })
        )
        break
      }
      case AssetEventType.addLabels: {
        setRowState(oldRowState =>
          oldRowState.temporarilyAddedLabels === set.EMPTY
            ? oldRowState
            : object.merge(oldRowState, { temporarilyAddedLabels: set.EMPTY })
        )
        const labels = asset.labels
        if (
          event.ids.has(item.key) &&
          (labels == null || [...event.labelNames].some(label => !labels.includes(label)))
        ) {
          const newLabels = [
            ...(labels ?? []),
            ...[...event.labelNames].filter(label => labels?.includes(label) !== true),
          ]
          setAsset(object.merger({ labels: newLabels }))
          try {
            await smartAsset.setTags(newLabels)
          } catch (error) {
            setAsset(object.merger({ labels }))
            toastAndLog(null, error)
          }
        }
        break
      }
      case AssetEventType.removeLabels: {
        setRowState(oldRowState =>
          oldRowState.temporarilyAddedLabels === set.EMPTY
            ? oldRowState
            : object.merge(oldRowState, { temporarilyAddedLabels: set.EMPTY })
        )
        const labels = asset.labels
        if (
          event.ids.has(item.key) &&
          labels != null &&
          [...event.labelNames].some(label => labels.includes(label))
        ) {
          const newLabels = labels.filter(label => !event.labelNames.has(label))
          setAsset(object.merger({ labels: newLabels }))
          try {
            await smartAsset.setTags(newLabels)
          } catch (error) {
            setAsset(object.merger({ labels }))
            toastAndLog(null, error)
          }
        }
        break
      }
      case AssetEventType.deleteLabel: {
        setAsset(oldAsset => {
          // The IIFE is required to prevent TypeScript from narrowing this value.
          let found = (() => false)()
          const labels =
            oldAsset.labels?.filter(label => {
              if (label === event.labelName) {
                found = true
                return false
              } else {
                return true
              }
            }) ?? null
          return found ? object.merge(oldAsset, { labels }) : oldAsset
        })
        break
      }
    }
  })

  const clearDragState = React.useCallback(() => {
    setIsDraggedOver(false)
    setRowState(oldRowState =>
      oldRowState.temporarilyAddedLabels === set.EMPTY
        ? oldRowState
        : object.merge(oldRowState, { temporarilyAddedLabels: set.EMPTY })
    )
  }, [])

  const onDragOver = (event: React.DragEvent<Element>) => {
    const directoryKey =
      item.item.type === backendModule.AssetType.directory ? item.key : item.directoryKey
    const payload = drag.ASSET_ROWS.lookup(event)
    if (payload != null && payload.every(innerItem => innerItem.key !== directoryKey)) {
      event.preventDefault()
      if (item.item.type === backendModule.AssetType.directory) {
        setIsDraggedOver(true)
      }
    }
  }

  switch (asset.type) {
    case backendModule.AssetType.directory:
    case backendModule.AssetType.project:
    case backendModule.AssetType.file:
    case backendModule.AssetType.secret: {
      const innerProps: AssetRowInnerProps = {
        key,
        item,
        setItem,
        state,
        rowState,
        setRowState,
      }
      return (
        <>
          {!hidden && (
            <tr
              draggable
              tabIndex={-1}
              className={`h-8 transition duration-300 ease-in-out ${
                visibilityModule.CLASS_NAME[visibility]
              } ${isDraggedOver || selected ? 'selected' : ''}`}
              onClick={event => {
                unsetModal()
                onClick(innerProps, event)
                if (
                  smartAsset.type === backendModule.AssetType.directory &&
                  eventModule.isDoubleClick(event) &&
                  !rowState.isEditingName
                ) {
                  // This must be processed on the next tick, otherwise it will be overridden
                  // by the default click handler.
                  window.setTimeout(() => {
                    setSelected(false)
                  })
                  doToggleDirectoryExpansion(smartAsset, item.key)
                }
              }}
              onContextMenu={event => {
                if (allowContextMenu) {
                  event.preventDefault()
                  event.stopPropagation()
                  onContextMenu?.(innerProps, event)
                  setModal(
                    <AssetContextMenu
                      isCloud={isCloud}
                      innerProps={innerProps}
                      event={event}
                      eventTarget={
                        event.target instanceof HTMLElement ? event.target : event.currentTarget
                      }
                      doCopy={doCopy}
                      doCut={doCut}
                      doPaste={doPaste}
                      doDelete={doDelete}
                    />
                  )
                } else {
                  onContextMenu?.(innerProps, event)
                }
              }}
              onDragStart={event => {
                if (rowState.isEditingName || !isCloud) {
                  event.preventDefault()
                } else {
                  props.onDragStart?.(event)
                }
              }}
              onDragEnter={event => {
                if (dragOverTimeoutHandle.current != null) {
                  window.clearTimeout(dragOverTimeoutHandle.current)
                }
                if (smartAsset.type === backendModule.AssetType.directory) {
                  dragOverTimeoutHandle.current = window.setTimeout(() => {
                    doToggleDirectoryExpansion(smartAsset, item.key, true)
                  }, DRAG_EXPAND_DELAY_MS)
                }
                // Required because `dragover` does not fire on `mouseenter`.
                props.onDragOver?.(event)
                onDragOver(event)
              }}
              onDragOver={event => {
                props.onDragOver?.(event)
                onDragOver(event)
              }}
              onDragEnd={event => {
                clearDragState()
                props.onDragEnd?.(event)
              }}
              onDragLeave={event => {
                if (
                  dragOverTimeoutHandle.current != null &&
                  (!(event.relatedTarget instanceof Node) ||
                    !event.currentTarget.contains(event.relatedTarget))
                ) {
                  window.clearTimeout(dragOverTimeoutHandle.current)
                }
                if (event.currentTarget === event.target) {
                  clearDragState()
                }
                props.onDragLeave?.(event)
              }}
              onDrop={event => {
                props.onDrop?.(event)
                clearDragState()
                const [newParentKey, newParent] =
                  item.item.type === backendModule.AssetType.directory
                    ? [item.key, item.item]
                    : [item.directoryKey, item.directory]
                const payload = drag.ASSET_ROWS.lookup(event)
                if (payload != null && payload.every(innerItem => innerItem.key !== newParentKey)) {
                  event.preventDefault()
                  event.stopPropagation()
                  unsetModal()
                  doToggleDirectoryExpansion(newParent, newParentKey, true)
                  const ids = new Set(payload.map(dragItem => dragItem.key))
                  dispatchAssetEvent({ type: AssetEventType.move, newParentKey, newParent, ids })
                }
              }}
            >
              {columns.map(column => {
                // This is a React component even though it does not contain JSX.
                // eslint-disable-next-line no-restricted-syntax
                const Render = columnModule.COLUMN_RENDERER[column]
                return (
                  <td key={column} className={columnUtils.COLUMN_CSS_CLASS[column]}>
                    <Render
                      item={item}
                      setItem={setItem}
                      selected={selected}
                      setSelected={setSelected}
                      isSoleSelectedItem={isSoleSelectedItem}
                      state={state}
                      rowState={rowState}
                      setRowState={setRowState}
                    />
                  </td>
                )
              })}
            </tr>
          )}
          {selected && allowContextMenu && insertionVisibility !== Visibility.hidden && (
            // This is a copy of the context menu, since the context menu registers keyboard
            // shortcut handlers. This is a bit of a hack, however it is preferable to duplicating
            // the entire context menu (once for the keyboard actions, once for the JSX).
            <AssetContextMenu
              hidden
              isCloud={isCloud}
              innerProps={{
                key,
                item,
                setItem,
                state,
                rowState,
                setRowState,
              }}
              event={{ pageX: 0, pageY: 0 }}
              eventTarget={null}
              doCopy={doCopy}
              doCut={doCut}
              doPaste={doPaste}
              doDelete={doDelete}
            />
          )}
        </>
      )
    }
    case backendModule.AssetType.specialLoading: {
      return hidden ? null : (
        <tr>
          <td colSpan={columns.length} className="rounded-rows-skip-level border-r p-0">
            <div
              className={`flex justify-center rounded-full h-8 py-1 ${indent.indentClass(
                item.depth
              )}`}
            >
              <StatelessSpinner size={24} state={statelessSpinner.SpinnerState.loadingMedium} />
            </div>
          </td>
        </tr>
      )
    }
    case backendModule.AssetType.specialEmpty: {
      return hidden ? null : (
        <tr>
          <td colSpan={columns.length} className="rounded-rows-skip-level border-r p-0">
            <div
              className={`flex items-center rounded-full h-8 py-2 ${indent.indentClass(
                item.depth
              )}`}
            >
              <img src={BlankIcon} />
              {EMPTY_DIRECTORY_PLACEHOLDER}
            </div>
          </td>
        </tr>
      )
    }
  }
}
