/**
 * @file
 *
 * Modal for accepting the terms of service.
 */

import * as React from 'react'

import * as reactQuery from '@tanstack/react-query'
import * as router from 'react-router'
import * as twMerge from 'tailwind-merge'
import * as z from 'zod'

import * as authProvider from '#/providers/AuthProvider'
import * as localStorageProvider from '#/providers/LocalStorageProvider'
import * as textProvider from '#/providers/TextProvider'

import * as aria from '#/components/aria'
import * as ariaComponents from '#/components/AriaComponents'

import LocalStorage from '#/utilities/LocalStorage'

declare module '#/utilities/LocalStorage' {
  /**
   * Contains the latest terms of service version hash that the user has accepted.
   */
  interface LocalStorageData {
    readonly termsOfService: z.infer<typeof TERMS_OF_SERVICE_SCHEMA> | null
  }
}
const TERMS_OF_SERVICE_SCHEMA = z.object({ versionHash: z.string() })
LocalStorage.registerKey('termsOfService', { schema: TERMS_OF_SERVICE_SCHEMA })

export const latestTermsOfService = reactQuery.queryOptions({
  queryKey: ['termsOfService', 'currentVersion'],
  queryFn: () =>
    fetch(new URL('/eula.json', process.env.ENSO_CLOUD_ENSO_HOST))
      .then(response => {
        if (!response.ok) {
          throw new Error('Failed to fetch terms of service')
        } else {
          return response.json()
        }
      })
      .then(data => {
        const schema = z.object({ hash: z.string() })
        return schema.parse(data)
      }),
  refetchOnWindowFocus: true,
  refetchIntervalInBackground: true,
  // eslint-disable-next-line @typescript-eslint/no-magic-numbers
  refetchInterval: 1000 * 60 * 10, // 10 minutes
})

/**
 * Modal for accepting the terms of service.
 */
export function TermsOfServiceModal() {
  const { getText } = textProvider.useText()
  const { localStorage } = localStorageProvider.useLocalStorage()
  const checkboxId = React.useId()
  const { session } = authProvider.useAuth()

  const eula = reactQuery.useSuspenseQuery(latestTermsOfService)

  const latestVersionHash = eula.data.hash
  const localVersionHash = localStorage.get('termsOfService')?.versionHash

  const isLatest = latestVersionHash === localVersionHash
  const isAccepted = localVersionHash != null
  const shouldDisplay = !(isAccepted && isLatest)

  if (shouldDisplay) {
    return (
      <>
        <ariaComponents.Dialog
          title={getText('licenseAgreementTitle')}
          isKeyboardDismissDisabled
          isDismissable={false}
          hideCloseButton
          modalProps={{ isOpen: true }}
          testId="terms-of-service-modal"
          id="terms-of-service-modal"
        >
          <ariaComponents.Form
            testId="terms-of-service-form"
            schema={ariaComponents.Form.schema.object({
              agree: ariaComponents.Form.schema
                .boolean()
                // we accept only true
                .refine(value => value, getText('licenseAgreementCheckboxError')),
            })}
            onSubmit={() => {
              localStorage.set('termsOfService', { versionHash: latestVersionHash })
            }}
          >
            {({ register, formState }) => {
              const agreeError = formState.errors.agree
              const hasError = formState.errors.agree != null

              const checkboxRegister = register('agree')

              return (
                <>
                  <div className="pb-6 pt-2">
                    <div className="mb-1">
                      <div className="flex items-center gap-1.5 text-sm">
                        <div className="mt-0">
                          <aria.Input
                            type="checkbox"
                            className={twMerge.twMerge(
                              `flex size-4 cursor-pointer overflow-clip rounded-lg border border-primary outline-primary focus-visible:outline focus-visible:outline-2 ${hasError ? 'border-red-700 text-red-500 outline-red-500' : ''}`
                            )}
                            id={checkboxId}
                            aria-invalid={hasError}
                            {...checkboxRegister}
                            onInput={event => {
                              void checkboxRegister.onChange(event)
                            }}
                            data-testid="terms-of-service-checkbox"
                          />
                        </div>

                        <aria.Label htmlFor={checkboxId} className="text-sm">
                          {getText('licenseAgreementCheckbox')}
                        </aria.Label>
                      </div>

                      {agreeError && (
                        <p className="m-0 text-xs text-red-700" role="alert">
                          {agreeError.message}
                        </p>
                      )}
                    </div>

                    <ariaComponents.Button
                      variant="link"
                      target="_blank"
                      href="https://enso.org/eula"
                    >
                      {getText('viewLicenseAgreement')}
                    </ariaComponents.Button>
                  </div>

                  <ariaComponents.Form.FormError />

                  <ariaComponents.Form.Submit>{getText('accept')}</ariaComponents.Form.Submit>
                </>
              )
            }}
          </ariaComponents.Form>
        </ariaComponents.Dialog>
      </>
    )
  } else {
    return <router.Outlet context={session} />
  }
}