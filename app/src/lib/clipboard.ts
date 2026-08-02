/**
 * Copies text to the clipboard, falling back when the async Clipboard API
 * is unavailable.
 *
 * `navigator.clipboard` only exists in a secure context, and self-hosted
 * tools are routinely reached over plain http:// on a LAN — where the
 * property is simply undefined and an unguarded call throws. The fallback
 * uses the deprecated `execCommand('copy')`, which has no such requirement.
 *
 * @returns whether the text reached the clipboard
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // permission denied or a non-secure context that still exposed the API
    }
  }
  return copyWithExecCommand(text)
}

function copyWithExecCommand(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  // off-screen but still focusable — display:none would not be selectable
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-1000px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)

  try {
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(textarea)
  }
}
