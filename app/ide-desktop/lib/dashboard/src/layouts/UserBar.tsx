/** @file A toolbar containing chat and the user menu. */
import * as React from 'react'

import ChatIcon from 'enso-assets/chat.svg'
import DefaultUserIcon from 'enso-assets/default_user.svg'

import * as appUtils from '#/appUtils'

import * as billing from '#/hooks/billing'

import * as authProvider from '#/providers/AuthProvider'
import * as modalProvider from '#/providers/ModalProvider'
import * as textProvider from '#/providers/TextProvider'

import UserMenu from '#/layouts/UserMenu'

import * as aria from '#/components/aria'
import * as ariaComponents from '#/components/AriaComponents'
import * as paywall from '#/components/Paywall'
import Button from '#/components/styled/Button'
import FocusArea from '#/components/styled/FocusArea'

import InviteUsersModal from '#/modals/InviteUsersModal'
import ManagePermissionsModal from '#/modals/ManagePermissionsModal'

import * as backendModule from '#/services/Backend'
import type Backend from '#/services/Backend'

// ===============
// === UserBar ===
// ===============

/** Props for a {@link UserBar}. */
export interface UserBarProps {
  readonly backend: Backend | null
  /** When `true`, the element occupies space in the layout but is not visible.
   * Defaults to `false`. */
  readonly invisible?: boolean
  readonly isOnEditorPage: boolean
  readonly setIsHelpChatOpen: (isHelpChatOpen: boolean) => void
  readonly projectAsset: backendModule.ProjectAsset | null
  readonly setProjectAsset: React.Dispatch<React.SetStateAction<backendModule.ProjectAsset>> | null
  readonly doRemoveSelf: () => void
  readonly goToSettingsPage: () => void
  readonly onSignOut: () => void
}

/** A toolbar containing chat and the user menu. */
export default function UserBar(props: UserBarProps) {
  const { backend, invisible = false, isOnEditorPage, setIsHelpChatOpen } = props
  const { projectAsset, setProjectAsset, doRemoveSelf, goToSettingsPage, onSignOut } = props
  const { user } = authProvider.useNonPartialUserSession()
  const { setModal } = modalProvider.useSetModal()
  const { getText } = textProvider.useText()
  const { isFeatureUnderPaywall } = billing.usePaywall({ plan: user.plan })
  const self =
    projectAsset?.permissions?.find(
      backendModule.isUserPermissionAnd(permissions => permissions.user.userId === user.userId)
    ) ?? null
  const shouldShowShareButton =
    backend?.type === backendModule.BackendType.remote &&
    isOnEditorPage &&
    projectAsset != null &&
    setProjectAsset != null &&
    self != null
  const shouldShowUpgradeButton = isFeatureUnderPaywall('inviteUser')
  const shouldShowInviteButton =
    backend != null && !shouldShowShareButton && !shouldShowUpgradeButton

  return (
    <FocusArea active={!invisible} direction="horizontal">
      {innerProps => (
        <div className="bg-primary/5 pt-0.5">
          <div
            className="flex h-[46px] shrink-0 cursor-default items-center gap-user-bar pl-icons-x pr-3"
            {...innerProps}
          >
            <ariaComponents.Button
              variant="icon"
              size="custom"
              className="mr-1"
              icon={ChatIcon}
              aria-label={getText('openHelpChat')}
              onPress={() => {
                setIsHelpChatOpen(true)
              }}
            />

            {shouldShowUpgradeButton && (
              <paywall.PaywallDialogButton feature={'inviteUser'} size="medium" variant="tertiary">
                {getText('invite')}
              </paywall.PaywallDialogButton>
            )}

            {shouldShowInviteButton && (
              <ariaComponents.DialogTrigger>
                <ariaComponents.Button size="medium" variant="tertiary">
                  {getText('invite')}
                </ariaComponents.Button>

                <InviteUsersModal />
              </ariaComponents.DialogTrigger>
            )}

            <ariaComponents.Button variant="primary" size="medium" href={appUtils.SUBSCRIBE_PATH}>
              {getText('upgrade')}
            </ariaComponents.Button>

            {shouldShowShareButton && (
              <ariaComponents.Button
                size="medium"
                variant="tertiary"
                aria-label={getText('shareButtonAltText')}
                onPress={() => {
                  setModal(
                    <ManagePermissionsModal
                      backend={backend}
                      item={projectAsset}
                      setItem={setProjectAsset}
                      self={self}
                      doRemoveSelf={doRemoveSelf}
                      eventTarget={null}
                    />
                  )
                }}
              >
                <aria.Text slot="label">{getText('share')}</aria.Text>
              </ariaComponents.Button>
            )}
            <Button
              active
              mask={false}
              alt={getText('userMenuAltText')}
              image={user.profilePicture ?? DefaultUserIcon}
              buttonClassName="rounded-full after:rounded-full"
              className="h-row-h w-row-h rounded-full"
              onPress={() => {
                setModal(<UserMenu goToSettingsPage={goToSettingsPage} onSignOut={onSignOut} />)
              }}
            />
            {/* Required for shortcuts to work. */}
            <div className="hidden">
              <UserMenu hidden goToSettingsPage={goToSettingsPage} onSignOut={onSignOut} />
            </div>
          </div>
        </div>
      )}
    </FocusArea>
  )
}
