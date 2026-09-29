// csrf_token은 HttpOnly가 아니라 JS에서 읽어 X-CSRF-Token 헤더로 되돌려 보내야 한다(설계서 §17.3).
export function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}
