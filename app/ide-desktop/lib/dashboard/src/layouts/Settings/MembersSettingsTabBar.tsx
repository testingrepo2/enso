/** @file Button bar for managing organization members. */
import * as React from 'react'

import * as textProvider from '#/providers/TextProvider'

import * as ariaComponents from '#/components/AriaComponents'

import InviteUsersModal from '#/modals/InviteUsersModal'

// =============================
// === MembersSettingsTabBar ===
// =============================

/** Button bar for managing organization members. */
export default function MembersSettingsTabBar() {
  const { getText } = textProvider.useText()

  return (
    <ariaComponents.ButtonGroup>
      <ariaComponents.DialogTrigger>
        <ariaComponents.Button variant="bar">{getText('inviteMembers')}</ariaComponents.Button>

        <InviteUsersModal />
      </ariaComponents.DialogTrigger>
    </ariaComponents.ButtonGroup>
  )
}
