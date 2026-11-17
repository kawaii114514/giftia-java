import { useEffect, useRef } from 'react'

/** Keep keyboard navigation inside an open dialog and return focus to its trigger. */
export function useDialogFocus(onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = ref.current
    if (!dialog) return
    const controls = () =>
      Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), summary, a[href], [tabindex="0"]'
        )
      ).filter((el) => !el.hidden && getComputedStyle(el).display !== 'none')
    ;(controls()[0] || dialog).focus()
    // Saving or cancelling an editor can remove the focused control entirely.
    const observer = new MutationObserver(() => {
      if (!dialog.contains(document.activeElement)) (controls()[0] || dialog).focus()
    })
    observer.observe(dialog, { childList: true, subtree: true })
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = controls()
      const first = items[0]
      const last = items[items.length - 1]
      if (!first) {
        event.preventDefault()
        dialog.focus()
        return
      }
      if (
        event.shiftKey &&
        (document.activeElement === first || document.activeElement === dialog)
      ) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      observer.disconnect()
      document.removeEventListener('keydown', onKeyDown, true)
      previous?.focus()
    }
  }, [])
  return ref
}
